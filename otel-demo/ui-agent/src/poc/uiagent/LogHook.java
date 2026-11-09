package poc.uiagent;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Application logs into java_logs. java.util.logging gets a Handler on the root logger right away.
 * log4j 1.x, log4j 2, and logback get an appender (a Proxy, so nothing here links to them) as soon as
 * their classes are on the class path; a daemon thread looks every few seconds because apps often
 * set logging up well after premain. System.out and System.err are teed line by line.
 */
public final class LogHook implements Runnable {
    private final Telemetry t;
    private boolean log4j1, log4j2, logback;
    private volatile boolean stopped;
    private Handler julHandler;
    private PrintStream origOut, origErr, teeOut, teeErr;

    public LogHook(Telemetry t) { this.t = t; }

    /** log4j and logback appenders stay attached and are dropped by Telemetry; those APIs vary by version. */
    public void stop() {
        stopped = true;
        if (julHandler != null) Logger.getLogger("").removeHandler(julHandler);
        if (teeOut != null && System.out == teeOut) System.setOut(origOut);
        if (teeErr != null && System.err == teeErr) System.setErr(origErr);
    }

    public void install() {
        try { hookJul(); } catch (Throwable e) { Agent.debug("jul: " + e); }
        if (t.cfg.captureStdout) {
            try { hookStreams(); } catch (Throwable e) { Agent.debug("stdout: " + e); }
        }
        Thread th = new Thread(this, "ui-agent-logs");
        th.setDaemon(true);
        th.start();
    }

    public void run() {
        int tick = 0;
        while (!stopped) {
            try {
                Thread.sleep(tick++ < 20 ? 3000 : 15000);
                if (!log4j1) log4j1 = hookLog4j1();
                if (!log4j2) log4j2 = hookLog4j2();
                if (!logback) logback = hookLogback();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                Agent.debug("log hook: " + e);
            }
        }
    }

    // ---- java.util.logging --------------------------------------------------------------------

    private void hookJul() {
        Logger root = Logger.getLogger("");
        root.addHandler(julHandler = new Handler() {
            public void publish(LogRecord r) {
                if (r == null || r.getLoggerName() != null && r.getLoggerName().startsWith("poc.uiagent")) return;
                String msg = r.getMessage();
                try {
                    if (r.getParameters() != null && r.getParameters().length > 0) msg = java.text.MessageFormat.format(msg, r.getParameters());
                } catch (Throwable ignored) { }
                t.log(level(r.getLevel()), r.getLoggerName(), Thread.currentThread().getName(), msg, "app_log",
                        r.getThrown() == null ? null : Errors.stack(r.getThrown()));
            }
            public void flush() { }
            public void close() { }
        });
        Agent.debug("jul hooked");
    }

    static String level(Level l) {
        if (l == null) return "INFO";
        int v = l.intValue();
        if (v >= Level.SEVERE.intValue()) return "ERROR";
        if (v >= Level.WARNING.intValue()) return "WARN";
        if (v >= Level.INFO.intValue()) return "INFO";
        if (v >= Level.FINE.intValue()) return "DEBUG";
        return "TRACE";
    }

    // ---- log4j 1.x ----------------------------------------------------------------------------

    private boolean hookLog4j1() {
        if (Reflect.cls("org.apache.log4j.Logger") == null || Reflect.cls("org.apache.log4j.Appender") == null) return false;
        Object appender = Reflect.proxy("org.apache.log4j.Appender", new Reflect.Handler() {
            protected Object handle(String m, Object[] a, Method mm) {
                if (m.equals("doAppend")) {
                    Object ev = a[0];
                    Object thrown = Reflect.call(ev, "getThrowableStrRep");
                    t.log(String.valueOf(Reflect.call(ev, "getLevel")), (String) Reflect.call(ev, "getLoggerName"),
                            (String) Reflect.call(ev, "getThreadName"), String.valueOf(Reflect.call(ev, "getRenderedMessage")), "app_log", join(thrown));
                } else if (m.equals("getName")) return "ui-agent";
                else if (m.equals("requiresLayout")) return Boolean.FALSE;
                return dflt(mm.getReturnType());
            }
        });
        Object root = Reflect.callStatic("org.apache.log4j.Logger", "getRootLogger", new Class<?>[0]);
        if (root == null || appender == null) return false;
        Reflect.call(root, "addAppender", new Class<?>[] { Reflect.cls("org.apache.log4j.Appender") }, appender);
        Agent.debug("log4j 1.x hooked");
        return true;
    }

    static String join(Object strings) {
        if (!(strings instanceof String[])) return null;
        StringBuilder b = new StringBuilder();
        for (String s : (String[]) strings) b.append(s).append('\n');
        return b.length() == 0 ? null : b.toString();
    }

    // ---- log4j 2 ------------------------------------------------------------------------------

    private boolean hookLog4j2() {
        if (Reflect.cls("org.apache.logging.log4j.core.LoggerContext") == null) return false;
        Object appender = Reflect.proxy("org.apache.logging.log4j.core.Appender", new Reflect.Handler() {
            boolean started;
            protected Object handle(String m, Object[] a, Method mm) {
                if (m.equals("append")) {
                    Object ev = a[0];
                    Object msg = Reflect.call(ev, "getMessage");
                    Object thrown = Reflect.call(ev, "getThrown");
                    t.log(String.valueOf(Reflect.call(ev, "getLevel")), (String) Reflect.call(ev, "getLoggerName"),
                            (String) Reflect.call(ev, "getThreadName"), String.valueOf(Reflect.call(msg, "getFormattedMessage")), "app_log",
                            thrown instanceof Throwable ? Errors.stack((Throwable) thrown) : null);
                } else if (m.equals("getName")) return "ui-agent";
                else if (m.equals("start")) started = true;
                else if (m.equals("stop")) started = false;
                else if (m.equals("isStarted")) return Boolean.valueOf(started);
                else if (m.equals("isStopped")) return Boolean.valueOf(!started);
                else if (m.equals("ignoreExceptions")) return Boolean.TRUE;
                else if (m.equals("getState")) return Reflect.staticField("org.apache.logging.log4j.core.LifeCycle$State", started ? "STARTED" : "STOPPED");
                return dflt(mm.getReturnType());
            }
        });
        Object ctx = Reflect.callStatic("org.apache.logging.log4j.LogManager", "getContext", new Class<?>[] { boolean.class }, Boolean.FALSE);
        if (!Reflect.is(ctx, "org.apache.logging.log4j.core.LoggerContext") || appender == null) return false;
        Reflect.call(appender, "start");
        Object config = Reflect.call(ctx, "getConfiguration");
        Object rootCfg = Reflect.call(config, "getRootLogger");
        Reflect.call(rootCfg, "addAppender", new Class<?>[] { Reflect.cls("org.apache.logging.log4j.core.Appender"),
                Reflect.cls("org.apache.logging.log4j.Level"), Reflect.cls("org.apache.logging.log4j.core.Filter") },
                appender, Reflect.staticField("org.apache.logging.log4j.Level", "ALL"), null);
        Reflect.call(ctx, "updateLoggers");
        Agent.debug("log4j 2 hooked");
        return true;
    }

    // ---- logback ------------------------------------------------------------------------------

    private boolean hookLogback() {
        if (Reflect.cls("ch.qos.logback.classic.Logger") == null) return false;
        Object appender = Reflect.proxy("ch.qos.logback.core.Appender", new Reflect.Handler() {
            boolean started;
            protected Object handle(String m, Object[] a, Method mm) {
                if (m.equals("doAppend")) {
                    Object ev = a[0];
                    Object tp = Reflect.call(ev, "getThrowableProxy");
                    String stack = null;
                    if (tp != null) stack = Reflect.call(tp, "getClassName") + ": " + Reflect.call(tp, "getMessage");
                    t.log(String.valueOf(Reflect.call(ev, "getLevel")), (String) Reflect.call(ev, "getLoggerName"),
                            (String) Reflect.call(ev, "getThreadName"), String.valueOf(Reflect.call(ev, "getFormattedMessage")), "app_log", stack);
                } else if (m.equals("getName")) return "ui-agent";
                else if (m.equals("start")) started = true;
                else if (m.equals("stop")) started = false;
                else if (m.equals("isStarted")) return Boolean.valueOf(started);
                else if (m.equals("getFilterChainDecision")) return Reflect.staticField("ch.qos.logback.core.spi.FilterReply", "NEUTRAL");
                return dflt(mm.getReturnType());
            }
        });
        Object root = Reflect.callStatic("org.slf4j.LoggerFactory", "getLogger", new Class<?>[] { String.class }, "ROOT");
        if (!Reflect.is(root, "ch.qos.logback.classic.Logger") || appender == null) return false;
        Reflect.call(appender, "start");
        Reflect.call(root, "addAppender", new Class<?>[] { Reflect.cls("ch.qos.logback.core.Appender") }, appender);
        Agent.debug("logback hooked");
        return true;
    }

    // ---- System.out / System.err --------------------------------------------------------------

    private void hookStreams() {
        origOut = System.out;
        origErr = System.err;
        Agent.out = origErr;
        teeOut = new PrintStream(new Tee(origOut, "INFO", "stdout"), true);
        teeErr = new PrintStream(new Tee(origErr, "ERROR", "stderr"), true);
        System.setOut(teeOut);
        System.setErr(teeErr);
    }

    /**
     * Passes bytes through and reports each complete line. Skips the agent's own lines. A printed
     * stack trace ("Exception in thread ..." then "\tat ..." lines) is one record with the frames in
     * exception_stacktrace, not one record per frame: a line is held for 300 ms so continuation lines
     * can join it.
     */
    final class Tee extends OutputStream {
        private final PrintStream through;
        private final String severity, kind;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final StringBuilder pending = new StringBuilder();
        private String pendingThread;
        private java.util.TimerTask flushTask;

        Tee(PrintStream through, String severity, String kind) {
            this.through = through;
            this.severity = severity;
            this.kind = kind;
        }

        public void write(int b) {
            through.write(b);
            if (b == '\n') emit(); else if (line.size() < 4000) line.write(b);
        }

        public void write(byte[] b, int off, int len) {
            through.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') emit(); else if (line.size() < 4000) line.write(b[i]);
            }
        }

        public void flush() { through.flush(); }

        private void emit() {
            String s;
            try { s = line.toString("UTF-8"); } catch (Exception e) { s = line.toString(); }
            line.reset();
            if (s.length() == 0 || s.startsWith("[ui-agent]")) return;
            // log4j's ConsoleAppender and JUL's ConsoleHandler also print here; those lines were already
            // captured through the logger, so skip the common patterns.
            if (s.matches("^\\d+ \\[.*\\] (TRACE|DEBUG|INFO|WARN|ERROR|FATAL) .*") || s.matches("^(SEVERE|WARNING|INFO|CONFIG|FINE|FINER|FINEST): .*")
                    || s.matches("^\\w{3} \\d{1,2}, \\d{4} .*(AM|PM) .*")) return;
            synchronized (pending) {
                boolean continuation = s.startsWith("\tat ") || s.startsWith("\t... ") || s.startsWith("Caused by: ")
                        || s.startsWith("\tSuppressed: ") || s.startsWith("Suppressed: ");
                if (continuation && pending.length() > 0) {
                    if (pending.length() < 8000) pending.append('\n').append(s);
                } else {
                    flushPending();
                    pending.append(s);
                    pendingThread = Thread.currentThread().getName();
                }
                if (flushTask != null) flushTask.cancel();
                flushTask = new java.util.TimerTask() {
                    public void run() { synchronized (pending) { flushPending(); } }
                };
                TIMER.schedule(flushTask, 300);
            }
        }

        private void flushPending() {
            if (pending.length() == 0) return;
            String all = pending.toString();
            pending.setLength(0);
            int nl = all.indexOf('\n');
            String body = nl < 0 ? all : all.substring(0, nl);
            String stack = nl < 0 ? null : all.substring(nl + 1);
            t.log(severity, kind, pendingThread, body, kind, stack);
        }
    }

    private static final java.util.Timer TIMER = new java.util.Timer("ui-agent-tee", true);
}
