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
    public static final String VERSION = "0.1.0";
    static volatile boolean debug;
    static PrintStream out = System.err;
    private static volatile Telemetry telemetry;

    public static void premain(String args, Instrumentation inst) { start(args, "premain"); }
    public static void premain(String args) { start(args, "premain"); }
    public static void agentmain(String args, Instrumentation inst) { start(args, "agentmain"); }
    public static void agentmain(String args) { start(args, "agentmain"); }

    static synchronized void start(String args, String how) {
        if (telemetry != null) {
            debug("already running; ignoring second " + how);
            return;
        }
        try {
            Config cfg = new Config(args);
            debug = cfg.debug;
            out = System.err;
            Telemetry t = new Telemetry(cfg);
            telemetry = t;
            info("ui-agent " + VERSION + " (" + how + ") " + cfg + " session=" + t.sessionId);
            Errors.install(t);
            new SwingHook(t).install();
            new FxHook(t).install();
            new Health(t).install();
            new LogHook(t).install();
            t.log("INFO", "ui-agent", Thread.currentThread().getName(), "ui-agent started via " + how + " on " + System.getProperty("java.version"), "agent", null);
        } catch (Throwable e) {
            info("ui-agent failed to start: " + e);
            if (debug) e.printStackTrace(out);
        }
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
            attach(argv[1], argv.length > 2 ? argv[2] : null);
        } else if (argv.length >= 1 && argv[0].equals("list")) {
            listVms();
        } else if (argv.length == 1 && argv[0].equals("self-check")) {
            Telemetry.selfCheck();
            System.out.println("ok");
        } else {
            System.out.println("ui-agent " + VERSION);
            System.out.println("  at launch:    java -javaagent:/path/ui-agent.jar[=key=value,...] -jar app.jar");
            System.out.println("  env only:     JAVA_TOOL_OPTIONS=-javaagent:/path/ui-agent.jar");
            System.out.println("  running JVM:  java -jar ui-agent.jar attach <pid> [key=value,...]");
            System.out.println("  find JVMs:    java -jar ui-agent.jar list");
            System.out.println("Settings: endpoint, app, version, env, privacy, debug, idle_seconds (UI_AGENT_* env or -Dui.agent.*)");
            System.exit(argv.length == 0 ? 0 : 2);
        }
    }

    static void attach(String pid, String args) throws Exception {
        Class<?> vm = attachApi();
        Object machine = vm.getMethod("attach", String.class).invoke(null, pid);
        String jar = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        try {
            vm.getMethod("loadAgent", String.class, String.class).invoke(machine, jar, args);
            System.out.println("attached ui-agent to " + pid + " from " + jar);
        } finally {
            vm.getMethod("detach").invoke(machine);
        }
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

    /** com.sun.tools.attach.VirtualMachine: on Java 9+ it is in the jdk.attach module, before that in tools.jar. */
    static Class<?> attachApi() throws Exception {
        try {
            return Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (ClassNotFoundException e) {
            File home = new File(System.getProperty("java.home"));
            File[] candidates = { new File(home, "lib/tools.jar"), new File(home.getParentFile(), "lib/tools.jar") };
            for (File f : candidates) {
                if (f.isFile()) {
                    URLClassLoader cl = new URLClassLoader(new URL[] { f.toURI().toURL() }, Agent.class.getClassLoader());
                    return Class.forName("com.sun.tools.attach.VirtualMachine", true, cl);
                }
            }
            throw new IllegalStateException("No attach API: run this command with a JDK (tools.jar), not a JRE");
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
