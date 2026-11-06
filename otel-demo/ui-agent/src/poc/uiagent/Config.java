package poc.uiagent;

import java.util.HashMap;
import java.util.Map;

/**
 * Settings, in order: agent arguments ("key=value,key=value" after the jar path), system properties
 * (ui.agent.*), environment variables (UI_AGENT_*), then the default.
 */
public final class Config {
    public final String endpoint;
    public final String app;
    public final String version;
    public final String env;
    /** mask-values: never record table cells or typed text. allow: record visible text as-is. */
    public final String privacy;
    public final boolean debug;
    public final int healthSeconds;
    public final long freezeMillis;
    /** No mouse, key, or focus this long means the operator stepped away. The window can stay open. */
    public final long idleMillis;
    public final boolean captureStdout;

    private final Map<String, String> args = new HashMap<String, String>();

    public Config(String agentArgs) {
        if (agentArgs != null) {
            for (String kv : agentArgs.split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) args.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        endpoint = stripSlash(get("endpoint", "http://localhost:4318"));
        app = get("app", mainClass());
        version = get("version", implementationVersion());
        env = get("env", "lab");
        privacy = get("privacy", "mask-values");
        debug = "true".equals(get("debug", "false"));
        healthSeconds = Integer.parseInt(get("health_seconds", "10"));
        freezeMillis = Long.parseLong(get("freeze_ms", "500"));
        idleMillis = Long.parseLong(get("idle_seconds", "180")) * 1000L;
        captureStdout = "true".equals(get("stdout", "true"));
    }

    String get(String key, String dflt) {
        String v = args.get(key);
        if (v == null) v = System.getProperty("ui.agent." + key);
        if (v == null) v = System.getenv("UI_AGENT_" + key.toUpperCase());
        return v == null || v.length() == 0 ? dflt : v;
    }

    static String stripSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** The launched main class or jar, as "java" shows it, with the package dropped. */
    static String mainClass() {
        String cmd = System.getProperty("sun.java.command", "");
        if (cmd.length() == 0) return "java-app";
        String first = cmd.split(" ")[0];
        int slash = first.lastIndexOf('/');
        if (slash >= 0) first = first.substring(slash + 1);
        if (first.endsWith(".jar")) first = first.substring(0, first.length() - 4);
        int dot = first.lastIndexOf('.');
        return dot >= 0 ? first.substring(dot + 1) : first;
    }

    static String implementationVersion() {
        String cmd = System.getProperty("sun.java.command", "");
        String first = cmd.split(" ")[0];
        if (first.endsWith(".jar")) {
            java.util.jar.JarFile jar = null;
            try {
                jar = new java.util.jar.JarFile(first);
                String v = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
                if (v != null) return v;
            } catch (Exception ignored) {
            } finally {
                if (jar != null) try { jar.close(); } catch (Exception ignored) { }
            }
        }
        return "unknown";
    }

    public String toString() {
        return "endpoint=" + endpoint + " app=" + app + " version=" + version + " env=" + env + " privacy=" + privacy;
    }
}
