package poc.uiagent;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns UI events into the same records poc.js sends from browsers, so OpenObserve columns and the
 * dashboards line up: interaction spans (event_type, page.path, target.*, rum.session_id), view and
 * error rows with _rumdata names, log rows, and gauge/sum metrics.
 */
public final class Telemetry {
    private static final SecureRandom RND = new SecureRandom();
    public final Config cfg;
    public final String sessionId = hex(16);
    public final Exporter exporter;
    private final Map<String, String> viewIds = new HashMap<String, String>();
    private final Map<String, Long> viewStarts = new HashMap<String, Long>();
    private final Map<String, String> viewTypes = new HashMap<String, String>();
    private final Set<String> inventorySeen = new HashSet<String>();
    private long lastInput, presentSince, armedAt;
    private boolean focused;
    private String armedText = "", armedElement = "", armedPath = "";

    public Telemetry(Config cfg) {
        this.cfg = cfg;
        Map<String, Object> res = Json.map();
        res.put("service.name", cfg.app);
        res.put("service.version", cfg.version);
        res.put("deployment.environment.name", cfg.env);
        res.put("telemetry.sdk.name", "poc.ui-agent");
        res.put("telemetry.sdk.language", "java");
        res.put("telemetry.sdk.version", Agent.VERSION);
        res.put("process.runtime.name", System.getProperty("java.runtime.name"));
        res.put("process.runtime.version", System.getProperty("java.runtime.version"));
        res.put("host.name", hostName());
        res.put("os.user", System.getProperty("user.name"));
        this.exporter = new Exporter(cfg, Json.attrs(res));
        exporter.beforeShutdown = new Runnable() {
            public void run() {
                synchronized (Telemetry.this) { endPresence(System.currentTimeMillis()); }
            }
        };
    }

    /** Mouse, key, or a window coming forward. Throttled; the health tick turns this into presence time. */
    public void touch() {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastInput < 1000) return;
            lastInput = now;
        }
    }

    /** A page window is in front. Losing that ends the current presence stretch. */
    public void focus(boolean on) {
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
     */
    public void arm(String text, String element, String path) {
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (armedAt != 0 && now - armedAt < cfg.freezeMillis + 1500
                    && eq(text, armedText) && eq(element, armedElement)) return;
            armedAt = now;
            armedText = text == null ? "" : text;
            armedElement = element == null ? "" : element;
            armedPath = path == null ? "" : path;
        }
    }

    /** The armed control if it was handled around the time this ping went out, else null. */
    public Map<String, Object> hungControl(long sentAt) {
        synchronized (this) {
            if (!blame(sentAt, armedAt, cfg.freezeMillis)) return null;
            Map<String, Object> a = Json.map();
            a.put("target.text", armedText);
            a.put("target.element", armedElement);
            a.put("page.path", armedPath);
            return a;
        }
    }

    /** One row per page, label, and control type for the life of the process.
     *  ponytail: two controls with the same label on one page collapse. Add the xpath to the key if that shows up. */
    public void inventory(String path, String label, String element) {
        String key = (path == null ? "" : path) + "\0" + (element == null ? "" : element) + "\0" + (label == null ? "" : label);
        synchronized (inventorySeen) {
            if (!inventorySeen.add(key)) return;
        }
        Map<String, Object> a = rumCommon(path);
        a.put("type", "inventory");
        a.put("target.text", label == null ? "" : label);
        a.put("target.element", element == null ? "" : element);
        exporter.log(logRecord(9, "INFO", "inventory " + path + " [" + label + "] " + element, a));
        Agent.debug("inventory " + path + " [" + label + "] " + element);
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

    // ---- interactions -------------------------------------------------------------------------

    /** One span per click, change, or submit. Flushed right away, like poc.js does. */
    public void interaction(String eventType, String toolkit, String pagePath, String text, String element, String xpath, String id, String name) {
        long now = nowNanos();
        Map<String, Object> a = Json.map();
        a.put("event_type", eventType);
        a.put("ui.toolkit", toolkit);
        a.put("page.path", pagePath);
        a.put("target.text", text == null ? "" : text);
        a.put("target.element", element == null ? "" : element);
        a.put("target.xpath", xpath == null ? "" : xpath);
        a.put("target.id", id == null ? "" : id);
        a.put("target.name", name == null ? "" : name);
        a.put("rum.session_id", sessionId);
        a.put("url.full", pagePath);
        arm(text, element, pagePath);
        StringBuilder b = new StringBuilder(512);
        b.append("{\"traceId\":\"").append(hex(16)).append("\",\"spanId\":\"").append(hex(8)).append("\",\"name\":").append(Json.str(eventType))
         .append(",\"kind\":1,\"startTimeUnixNano\":\"").append(now).append("\",\"endTimeUnixNano\":\"").append(now + 1000000L)
         .append("\",\"attributes\":").append(Json.attrs(a)).append(",\"status\":{}}");
        exporter.span(b.toString());
        exporter.flush();
        Agent.debug(eventType + " " + pagePath + " [" + text + "] " + element);
    }

    // ---- views and errors (same column names as OpenObserve RUM, stream ui_views) -------------

    /** Starts a view for a window or tab key; ends whichever view that key had before. */
    public synchronized void viewStart(String key, String path, String loadingType) {
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

    public void error(String message, String stack, String source, String path) {
        Map<String, Object> a = rumCommon(path);
        a.put("type", "error");
        a.put("error_message", message);
        a.put("error_stack", stack);
        a.put("error_source", source);
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
        long now = nowNanos();
        StringBuilder b = new StringBuilder(512);
        b.append("{\"timeUnixNano\":\"").append(now).append("\",\"observedTimeUnixNano\":\"").append(now)
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
