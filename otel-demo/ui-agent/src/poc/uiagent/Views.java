package poc.uiagent;

/** The window and tab the user is on now, shared by the Swing and FX hooks. */
public final class Views {
    private static volatile String currentPath = "";
    /** The window (Swing Window or FX Stage) whose view is open under the "active" key. */
    static volatile Object activeWindow;
    static volatile boolean firstView = true;
    /** Config.titleTrim compiled, or null. Titles that carry a file name or a track number would otherwise each be a page of their own. */
    private static volatile java.util.regex.Pattern trim;

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

    static void setTrim(String regex) {
        trim = null;
        if (regex == null || regex.length() == 0) return;
        try {
            trim = java.util.regex.Pattern.compile(regex);
        } catch (Exception e) {
            Agent.info("title_trim ignored, not a regular expression: " + regex);
        }
    }

    static String trimmed(String title) {
        java.util.regex.Pattern p = trim;
        if (p == null || title == null) return title;
        return p.matcher(title).replaceAll("*").trim();
    }

    /** "/Window title/Tab title"; untitled windows are "/untitled". */
    public static String path(String windowTitle, String tab) {
        windowTitle = trimmed(windowTitle);
        tab = trimmed(tab);
        String w = windowTitle == null || windowTitle.trim().length() == 0 ? "untitled" : windowTitle.trim();
        String p = "/" + w.replace('/', ' ');
        if (tab != null && tab.trim().length() > 0) p += "/" + tab.trim().replace('/', ' ');
        return p;
    }

    static void selfCheck() {
        if (!"/Plot/Gain".equals(path("Plot", "Gain"))) throw new AssertionError("no trim");
        setTrim(" - .*$|\\d+");
        try {
            if (!"/Engagement *".equals(path("Engagement 12", null))) throw new AssertionError("number: " + path("Engagement 12", null));
            if (!"/Editor*/Tab *".equals(path("Editor - notes.txt", "Tab 3"))) throw new AssertionError("suffix: " + path("Editor - notes.txt", "Tab 3"));
        } finally {
            setTrim(null);
        }
    }
}
