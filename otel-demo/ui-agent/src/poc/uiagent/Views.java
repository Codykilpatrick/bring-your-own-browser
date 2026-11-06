package poc.uiagent;

/** The window and tab the user is on now, shared by the Swing and FX hooks. */
public final class Views {
    private static volatile String currentPath = "";
    /** The window (Swing Window or FX Stage) whose view is open under the "active" key. */
    static volatile Object activeWindow;
    static volatile boolean firstView = true;

    private Views() { }

    public static String currentPath() { return currentPath; }

    /** Opens a view for a window that just became the one the user is in. */
    static synchronized void activate(Telemetry t, Object window, String path) {
        activeWindow = window;
        currentPath = path;
        t.viewStart("active", path, firstView ? "initial_load" : "route_change");
        firstView = false;
    }

    /** A tab change inside the active window. */
    static synchronized void routeChange(Telemetry t, Object window, String path) {
        if (activeWindow != null && activeWindow != window) return;
        activeWindow = window;
        currentPath = path;
        t.viewStart("active", path, "route_change");
    }

    static synchronized void closed(Telemetry t, Object window) {
        if (activeWindow == window) {
            activeWindow = null;
            t.viewEnd("active");
        }
    }

    /** "/Window title/Tab title"; untitled windows are "/untitled". */
    public static String path(String windowTitle, String tab) {
        String w = windowTitle == null || windowTitle.trim().length() == 0 ? "untitled" : windowTitle.trim();
        String p = "/" + w.replace('/', ' ');
        if (tab != null && tab.trim().length() > 0) p += "/" + tab.trim().replace('/', ' ');
        return p;
    }
}
