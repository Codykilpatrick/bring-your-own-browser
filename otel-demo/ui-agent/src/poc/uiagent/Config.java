package poc.uiagent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings, in order: agent arguments ("key=value,key=value" after the jar path), the file named by
 * the config argument, system properties (ui.agent.*), environment variables (UI_AGENT_*), then the
 * default.
 *
 * The file is what attach-all reads, and one copy can serve every app on a machine. Lines before the
 * first [section] apply to all apps; each [app name] section has a match line (text found in the
 * JVM's main class or jar, as "list" prints it) and any settings of its own.
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
    /** A regular expression. Whatever it matches in a window, dialog, or tab title becomes "*". */
    public final String titleTrim;
    /** How often the list of controls seen so far is sent again. */
    public final long inventoryMillis;

    private final Map<String, String> args = new HashMap<String, String>();

    public Config(String agentArgs) {
        if (agentArgs != null) {
            for (String kv : agentArgs.split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) args.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        String file = args.get("config");
        if (file != null) {
            try {
                Map<String, Map<String, String>> conf = readFile(new File(file));
                String name = args.get("app");
                if (name == null) name = match(conf, System.getProperty("sun.java.command", ""));
                Map<String, String> merged = new HashMap<String, String>(conf.get(""));
                if (name != null) {
                    merged.put("app", name);
                    if (conf.get(name) != null) merged.putAll(conf.get(name));
                }
                merged.remove("match");
                for (Map.Entry<String, String> e : merged.entrySet()) {
                    if (!args.containsKey(e.getKey())) args.put(e.getKey(), e.getValue());
                }
            } catch (Exception e) {
                Agent.info("config " + file + " not read: " + e);
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
        titleTrim = get("title_trim", "");
        inventoryMillis = Long.parseLong(get("inventory_minutes", "60")) * 60000L;
    }

    /** Sections in file order; "" holds the lines before the first section. No escapes, so a regular expression is written as is. */
    static Map<String, Map<String, String>> parse(List<String> lines) {
        Map<String, Map<String, String>> out = new LinkedHashMap<String, Map<String, String>>();
        Map<String, String> section = new LinkedHashMap<String, String>();
        out.put("", section);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = new LinkedHashMap<String, String>();
                out.put(line.substring(1, line.length() - 1).trim(), section);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) section.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }

    static Map<String, Map<String, String>> readFile(File f) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            List<String> lines = new ArrayList<String>();
            for (String line = r.readLine(); line != null; line = r.readLine()) lines.add(line);
            return parse(lines);
        } finally {
            r.close();
        }
    }

    /** The first section whose match text appears in the JVM's command (main class or jar, then its arguments). */
    static String match(Map<String, Map<String, String>> conf, String command) {
        for (Map.Entry<String, Map<String, String>> e : conf.entrySet()) {
            String m = e.getValue().get("match");
            if (e.getKey().length() > 0 && m != null && m.length() > 0 && command != null && command.contains(m)) return e.getKey();
        }
        return null;
    }

    static void selfCheck() {
        Map<String, Map<String, String>> c = parse(java.util.Arrays.asList(
                "# shared", "endpoint = http://10.0.0.5:4318", "", "[sonar]", "match = sonar-console.jar", "title_trim = \\d+ = x",
                "[fire]", "match=demo.fc.FireControl"));
        if (!"http://10.0.0.5:4318".equals(c.get("").get("endpoint"))) throw new AssertionError("shared line");
        if (!"\\d+ = x".equals(c.get("sonar").get("title_trim"))) throw new AssertionError("value kept as written");
        if (!"sonar".equals(match(c, "/opt/apps/sonar-console.jar --site 3"))) throw new AssertionError("jar match");
        if (!"fire".equals(match(c, "demo.fc.FireControl"))) throw new AssertionError("class match");
        if (match(c, "org.apache.tools.ant.Main") != null) throw new AssertionError("no match");
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
        return "endpoint=" + endpoint + " app=" + app + " version=" + version + " env=" + env + " privacy=" + privacy
                + (titleTrim.length() > 0 ? " title_trim=" + titleTrim : "");
    }
}
