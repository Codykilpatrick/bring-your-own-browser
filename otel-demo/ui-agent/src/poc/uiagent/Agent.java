package poc.uiagent;

import java.io.File;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Entry points. premain for -javaagent (or JAVA_TOOL_OPTIONS), agentmain for a runtime attach, and
 * main for the attach command line: java -jar ui-agent.jar attach &lt;pid&gt; [key=value,...]
 */
public final class Agent {
    public static final String VERSION = "0.2.0";
    /** Set in the app's JVM while the agent runs, so attach-all can tell which JVMs already have it. */
    static final String RUNNING = "ui.agent.running";
    static volatile boolean debug;
    static PrintStream out = System.err;
    private static volatile Telemetry telemetry;
    private static SwingHook swing;
    private static FxHook fx;
    private static Health health;
    private static LogHook logs;

    public static void premain(String args, Instrumentation inst) { start(args, "premain"); }
    public static void premain(String args) { start(args, "premain"); }
    public static void agentmain(String args, Instrumentation inst) { start(args, "agentmain"); }
    public static void agentmain(String args) { start(args, "agentmain"); }

    static synchronized void start(String args, String how) {
        if ("stop".equals(args)) {
            stop();
            return;
        }
        if (telemetry != null) {
            debug("already running; ignoring second " + how);
            return;
        }
        try {
            Config cfg = new Config(args);
            debug = cfg.debug;
            out = System.err;
            Views.setTrim(cfg.titleTrim);
            Telemetry t = new Telemetry(cfg);
            telemetry = t;
            System.setProperty(RUNNING, VERSION);
            t.started(how);
            info("ui-agent " + VERSION + " (" + how + ") " + cfg + " session=" + t.sessionId);
            Errors.install(t);
            swing = new SwingHook(t);
            swing.install();
            fx = new FxHook(t);
            fx.install();
            health = new Health(t);
            health.install();
            logs = new LogHook(t);
            logs.install();
            t.log("INFO", "ui-agent", Thread.currentThread().getName(), "ui-agent started via " + how + " on " + System.getProperty("java.version"), "agent", null);
        } catch (Throwable e) {
            info("ui-agent failed to start: " + e);
            if (debug) e.printStackTrace(out);
        }
    }

    /**
     * Sends what is queued and goes quiet. The classes stay loaded and listeners already added to
     * controls stay in place as no-ops until the app restarts; a JVM cannot unload an agent.
     */
    static synchronized void stop() {
        Telemetry t = telemetry;
        if (t == null) {
            info("ui-agent is not running; nothing to stop");
            return;
        }
        telemetry = null;
        System.clearProperty(RUNNING);
        try {
            t.log("INFO", "ui-agent", Thread.currentThread().getName(), "ui-agent stopped", "agent", null);
            t.stop();
            if (swing != null) swing.stop();
            if (fx != null) fx.stop();
            if (health != null) health.stop();
            if (logs != null) logs.stop();
            Errors.uninstall();
            t.exporter.stop();
        } catch (Throwable e) {
            info("ui-agent stop: " + e);
        }
        swing = null;
        fx = null;
        health = null;
        logs = null;
        info("ui-agent stopped");
    }

    public static Telemetry telemetry() { return telemetry; }

    static void debug(String s) {
        if (debug) out.println("[ui-agent] " + s);
    }

    static void info(String s) {
        out.println("[ui-agent] " + s);
    }

    // ---- attach command line ------------------------------------------------------------------

    public static void main(String[] argv) throws Exception {
        if (argv.length >= 2 && argv[0].equals("attach")) {
            load(argv[1], argv.length > 2 ? argv[2] : null);
            System.out.println("attached ui-agent to " + argv[1] + " from " + agentJar());
        } else if (argv.length == 2 && argv[0].equals("stop")) {
            load(argv[1], "stop");
            System.out.println("stopped ui-agent in " + argv[1]);
        } else if (argv.length == 2 && argv[0].equals("attach-all")) {
            File conf = new File(argv[1]).getAbsoluteFile();
            if (!conf.isFile() || !conf.canRead()) {
                System.out.println("cannot read " + conf);
                System.exit(2);
            }
            if (attachAll(conf) > 0) System.exit(1);
        } else if (argv.length >= 1 && argv[0].equals("list")) {
            listVms();
        } else if (argv.length == 1 && argv[0].equals("self-check")) {
            Telemetry.selfCheck();
            Config.selfCheck();
            Views.selfCheck();
            System.out.println("ok");
        } else {
            System.out.println("ui-agent " + VERSION);
            System.out.println("  at launch:    java -javaagent:/path/ui-agent.jar[=key=value,...] -jar app.jar");
            System.out.println("  env only:     JAVA_TOOL_OPTIONS=-javaagent:/path/ui-agent.jar");
            System.out.println("  running JVM:  java -jar ui-agent.jar attach <pid> [key=value,...]");
            System.out.println("  every app:    java -jar ui-agent.jar attach-all <file>   (one [app] section per app, see README)");
            System.out.println("  stop it:      java -jar ui-agent.jar stop <pid>");
            System.out.println("  find JVMs:    java -jar ui-agent.jar list");
            System.out.println("Settings: endpoint, app, version, env, privacy, title_trim, debug, idle_seconds, config (UI_AGENT_* env or -Dui.agent.*)");
            System.exit(argv.length == 0 ? 0 : 2);
        }
    }

    static File agentJar() throws Exception {
        return new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsoluteFile();
    }

    static void load(String pid, String args) throws Exception {
        Class<?> vm = attachApi();
        Object machine = vm.getMethod("attach", String.class).invoke(null, pid);
        try {
            vm.getMethod("loadAgent", String.class, String.class).invoke(machine, agentJar().getPath(), args);
        } finally {
            vm.getMethod("detach").invoke(machine);
        }
    }

    /**
     * Attaches to every running JVM that a section of the file names, and leaves the rest alone. Each
     * app is told to read its settings from the same file. Returns how many attaches failed.
     * Only JVMs started by the user running this command are visible to it.
     */
    static int attachAll(File file) throws Exception {
        java.util.Map<String, java.util.Map<String, String>> conf = Config.readFile(file);
        Class<?> vm = attachApi();
        String self = Telemetry.pid();
        String me = uidOf("self");
        int attached = 0, already = 0, skipped = 0, otherUser = 0, failed = 0;
        for (Object d : (java.util.List<?>) vm.getMethod("list").invoke(null)) {
            String id = String.valueOf(d.getClass().getMethod("id").invoke(d));
            String name = String.valueOf(d.getClass().getMethod("displayName").invoke(d));
            if (id.equals(self)) continue;
            String app = Config.match(conf, name);
            if (app == null) {
                skipped++;
                System.out.println("skipped   " + id + "  " + name + "  (no section in " + file.getName() + " matches)");
                continue;
            }
            // root sees every user's JVMs but a JVM only takes an agent from its own user. Trying anyway
            // waits 10 s and makes the app print a thread dump, so say who to run this as instead.
            String owner = uidOf(id);
            if (me != null && owner != null && !owner.equals(me)) {
                otherUser++;
                System.out.println("other     " + id + "  " + app + "  (runs as " + userName(owner) + "; run: sudo -u " + userName(owner)
                        + " java -jar " + agentJar().getPath() + " attach-all " + file.getPath() + ")");
                continue;
            }
            try {
                Object machine = vm.getMethod("attach", String.class).invoke(null, id);
                try {
                    java.util.Properties props = (java.util.Properties) vm.getMethod("getSystemProperties").invoke(machine);
                    if (props.getProperty(RUNNING) != null) {
                        already++;
                        System.out.println("already   " + id + "  " + app + "  (ui-agent " + props.getProperty(RUNNING) + " is running in it)");
                        continue;
                    }
                    vm.getMethod("loadAgent", String.class, String.class).invoke(machine, agentJar().getPath(), "config=" + file.getPath() + ",app=" + app);
                    attached++;
                    System.out.println("attached  " + id + "  " + app + "  (Java " + props.getProperty("java.version") + ")");
                } finally {
                    vm.getMethod("detach").invoke(machine);
                }
            } catch (Throwable e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                failed++;
                System.out.println("failed    " + id + "  " + app + "  " + cause);
            }
        }
        System.out.println(attached + " attached, " + already + " already attached, " + skipped + " skipped, " + otherUser + " run by another user, "
                + failed + " failed. This run handles the apps started by " + System.getProperty("user.name") + "; run it as each user that runs an app.");
        return failed + otherUser;
    }

    /** The user id a process runs as, from /proc. Null where there is no /proc (the attach is then just tried). */
    static String uidOf(String pid) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/" + pid + "/status"));
            try {
                for (String line = r.readLine(); line != null; line = r.readLine()) {
                    // "Uid:  real  effective  saved  filesystem"
                    if (line.startsWith("Uid:")) return line.substring(4).trim().split("\\s+")[1];
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            // not Linux, or the process is gone
        }
        return null;
    }

    static String userName(String uid) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/etc/passwd"));
            try {
                for (String line = r.readLine(); line != null; line = r.readLine()) {
                    String[] f = line.split(":");
                    if (f.length > 2 && f[2].equals(uid)) return f[0];
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            // fall through to the number
        }
        return "#" + uid;
    }

    static void listVms() throws Exception {
        Class<?> vm = attachApi();
        java.util.List<?> list = (java.util.List<?>) vm.getMethod("list").invoke(null);
        for (Object d : list) {
            Object id = d.getClass().getMethod("id").invoke(d);
            Object name = d.getClass().getMethod("displayName").invoke(d);
            System.out.println(id + "\t" + name);
        }
    }

    /**
     * com.sun.tools.attach.VirtualMachine: on Java 9+ it is in the jdk.attach module, before that in
     * tools.jar. On a Java 8 JRE, tools.jar and libattach.so copied from a JDK 8 next to ui-agent.jar
     * stand in for the JDK.
     */
    static Class<?> attachApi() throws Exception {
        try {
            return Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (ClassNotFoundException e) {
            File home = new File(System.getProperty("java.home"));
            File[] candidates = { new File(home, "lib/tools.jar"), new File(home.getParentFile(), "lib/tools.jar"), new File(agentJar().getParentFile(), "tools.jar") };
            for (File f : candidates) {
                if (f.isFile()) {
                    final File dir = f.getParentFile();
                    URLClassLoader cl = new URLClassLoader(new URL[] { f.toURI().toURL() }, Agent.class.getClassLoader()) {
                        protected String findLibrary(String name) {
                            File lib = new File(dir, System.mapLibraryName(name));
                            return lib.isFile() ? lib.getPath() : null;
                        }
                    };
                    return Class.forName("com.sun.tools.attach.VirtualMachine", true, cl);
                }
            }
            throw new IllegalStateException("No attach API: put tools.jar and libattach.so from a JDK 8 next to " + agentJar() + ", or run this command with a JDK");
        }
    }

    /** Used by the hooks to call optional methods without a compile-time dependency. */
    static Object call(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getMethod(method, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable e) {
            return null;
        }
    }
}
