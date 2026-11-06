package poc.uiagent;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Uncaught exceptions become error rows (type = error) next to the views. The JVM default handler
 * is wrapped and the app's own handler, if any, still runs. Health re-checks every tick that the
 * default handler is still ours and re-wraps if the app replaced it later.
 */
public final class Errors implements Thread.UncaughtExceptionHandler {
    private static volatile Errors current;
    private final Telemetry t;
    private final Thread.UncaughtExceptionHandler previous;

    private Errors(Telemetry t, Thread.UncaughtExceptionHandler previous) {
        this.t = t;
        this.previous = previous;
    }

    public static void install(Telemetry t) {
        wrapDefault(t);
        // Java 6 dispatches event-thread exceptions through this property, not the default handler.
        if (System.getProperty("java.version", "").startsWith("1.6") && System.getProperty("sun.awt.exception.handler") == null) {
            System.setProperty("sun.awt.exception.handler", EdtHandler.class.getName());
        }
    }

    static void wrapDefault(Telemetry t) {
        Thread.UncaughtExceptionHandler now = Thread.getDefaultUncaughtExceptionHandler();
        if (now instanceof Errors) return;
        Errors e = new Errors(t, now);
        current = e;
        Thread.setDefaultUncaughtExceptionHandler(e);
    }

    public void uncaughtException(Thread thread, Throwable ex) {
        report(t, thread, ex);
        if (previous != null) previous.uncaughtException(thread, ex);
        else {
            System.err.print("Exception in thread \"" + thread.getName() + "\" ");
            ex.printStackTrace();
        }
    }

    public static void report(Telemetry t, Thread thread, Throwable ex) {
        try {
            String source = thread.getName().startsWith("AWT-EventQueue") ? "swing"
                    : thread.getName().startsWith("JavaFX") ? "javafx" : "thread:" + thread.getName();
            t.error(ex.toString(), stack(ex), source, Views.currentPath());
            t.log("ERROR", "uncaught", thread.getName(), ex.toString(), "uncaught_exception", stack(ex));
        } catch (Throwable ignored) {
        }
    }

    public static String stack(Throwable ex) {
        StringWriter w = new StringWriter();
        ex.printStackTrace(new PrintWriter(w));
        String s = w.toString();
        return s.length() > 8000 ? s.substring(0, 8000) : s;
    }

    static Errors current() { return current; }
}
