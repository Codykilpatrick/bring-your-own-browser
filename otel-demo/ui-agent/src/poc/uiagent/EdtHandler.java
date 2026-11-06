package poc.uiagent;

/**
 * Java 6 event-thread exception handler, named by the sun.awt.exception.handler system property.
 * The event queue makes one with the no-arg constructor and calls handle(Throwable).
 */
public final class EdtHandler {
    public EdtHandler() { }

    public void handle(Throwable ex) {
        Telemetry t = Agent.telemetry();
        if (t != null) Errors.report(t, Thread.currentThread(), ex);
        System.err.print("Exception in thread \"" + Thread.currentThread().getName() + "\" ");
        ex.printStackTrace();
    }
}
