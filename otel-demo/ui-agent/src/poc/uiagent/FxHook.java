package poc.uiagent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * JavaFX through reflection only, so the same jar runs on JVMs without FX. A daemon thread waits for
 * the FX toolkit, then once a second (on the FX thread) looks for new windows and scenes. Each scene
 * gets event filters for clicks and actions; menu items and tab panes get listeners of their own.
 */
public final class FxHook implements Runnable {
    private static volatile boolean fxRunning;
    private final Telemetry t;
    private final boolean maskValues;
    private final Map<Object, Object> hooked = Collections.synchronizedMap(new WeakHashMap<Object, Object>());
    private final Map<Object, Object> shownWindows = Collections.synchronizedMap(new WeakHashMap<Object, Object>());
    private Object lastEvent;
    /** The window scan() is walking. Menu items are not in the scene graph and cannot say which window they are in. */
    private Object scanWindow;
    private Object actionHandler, clickHandler, releaseHandler, activityHandler;
    private volatile boolean stopped;

    public FxHook(Telemetry t) {
        this.t = t;
        this.maskValues = !"allow".equals(t.cfg.privacy);
    }

    public void install() {
        Thread th = new Thread(this, "ui-agent-fx");
        th.setDaemon(true);
        th.start();
    }

    /** Ends the scan thread. Scene filters already added stay and are dropped by Telemetry. */
    public void stop() { stopped = true; }

    /** True once Platform.runLater accepts work. */
    public static boolean runLater(Runnable r) {
        if (Reflect.cls("javafx.application.Platform") == null) return false;
        try {
            Class<?> p = Reflect.cls("javafx.application.Platform");
            p.getMethod("runLater", Runnable.class).invoke(null, r);
            fxRunning = true;
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isRunning() { return fxRunning; }

    public void run() {
        final Runnable scan = new Runnable() {
            public void run() {
                try { scan(); } catch (Throwable e) { Agent.debug("fx scan: " + e); }
            }
        };
        boolean announced = false;
        while (!stopped) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            if (Reflect.cls("javafx.stage.Window") == null) continue;
            if (runLater(scan) && !announced) {
                announced = true;
                Agent.debug("fx hook active");
            }
        }
    }

    // ---- scanning (FX thread) -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    void scan() {
        if (actionHandler == null) makeHandlers();
        List<Object> windows = new ArrayList<Object>();
        Object list = Reflect.callStatic("javafx.stage.Window", "getWindows", new Class<?>[0]);
        if (list == null) list = Reflect.callStatic("com.sun.javafx.stage.StageHelper", "getStages", new Class<?>[0]);
        if (list instanceof List) windows.addAll((List<Object>) list);
        for (Object w : windows) {
            if (Reflect.is(w, "javafx.stage.PopupWindow")) continue;
            Object scene = Reflect.call(w, "getScene");
            if (scene != null && hooked.put(scene, Boolean.TRUE) == null) hookScene(scene);
            scanWindow = w;
            if (scene != null) walk(Reflect.call(scene, "getRoot"));
            scanWindow = null;
            Boolean showing = (Boolean) Reflect.call(w, "isShowing");
            if (Boolean.TRUE.equals(showing) && shownWindows.put(w, Boolean.TRUE) == null) windowShown(w);
        }
    }

    private void makeHandlers() {
        actionHandler = Reflect.proxy("javafx.event.EventHandler", new Reflect.Handler() {
            protected Object handle(String m, Object[] a, Method mm) {
                if (m.equals("handle")) onAction(a[0]);
                return null;
            }
        });
        clickHandler = Reflect.proxy("javafx.event.EventHandler", new Reflect.Handler() {
            protected Object handle(String m, Object[] a, Method mm) {
                if (m.equals("handle")) onClick(a[0], false);
                return null;
            }
        });
        releaseHandler = Reflect.proxy("javafx.event.EventHandler", new Reflect.Handler() {
            protected Object handle(String m, Object[] a, Method mm) {
                if (m.equals("handle")) onClick(a[0], true);
                return null;
            }
        });
        activityHandler = Reflect.proxy("javafx.event.EventHandler", new Reflect.Handler() {
            protected Object handle(String m, Object[] a, Method mm) {
                if (m.equals("handle")) t.touch();
                return null;
            }
        });
    }

    private void hookScene(Object scene) {
        Class<?> eventType = Reflect.cls("javafx.event.EventType");
        Class<?> handler = Reflect.cls("javafx.event.EventHandler");
        Class<?>[] sig = { eventType, handler };
        Reflect.call(scene, "addEventFilter", sig, Reflect.staticField("javafx.event.ActionEvent", "ACTION"), actionHandler);
        Reflect.call(scene, "addEventFilter", sig, Reflect.staticField("javafx.scene.input.MouseEvent", "MOUSE_CLICKED"), clickHandler);
        Reflect.call(scene, "addEventFilter", sig, Reflect.staticField("javafx.scene.input.MouseEvent", "MOUSE_RELEASED"), releaseHandler);
        Reflect.call(scene, "addEventFilter", sig, Reflect.staticField("javafx.scene.input.MouseEvent", "MOUSE_PRESSED"), activityHandler);
        Reflect.call(scene, "addEventFilter", sig, Reflect.staticField("javafx.scene.input.MouseEvent", "MOUSE_MOVED"), activityHandler);
        Reflect.call(scene, "addEventFilter", sig, Reflect.staticField("javafx.scene.input.KeyEvent", "KEY_PRESSED"), activityHandler);
        Agent.debug("fx scene hooked");
    }

    /** Menu bars, context menus, menu buttons, and tab panes need their own listeners. */
    @SuppressWarnings("unchecked")
    private void walk(Object node) {
        if (node == null) return;
        if (Reflect.is(node, "javafx.scene.control.MenuBar")) {
            for (Object menu : (List<Object>) Reflect.call(node, "getMenus")) hookMenuItems(menu);
        } else if (Reflect.is(node, "javafx.scene.control.MenuButton")) {
            hookItems((List<Object>) Reflect.call(node, "getItems"));
        } else if (Reflect.is(node, "javafx.scene.control.TabPane")) {
            hookTabPane(node);
        }
        if (Reflect.is(node, "javafx.scene.control.Control")) {
            Object cm = Reflect.call(node, "getContextMenu");
            if (cm != null) hookItems((List<Object>) Reflect.call(cm, "getItems"));
        }
        if (isInventoryControl(node) && hooked.put(node, Boolean.TRUE) == null) note(node);
        if (Reflect.is(node, "javafx.scene.Parent")) {
            for (Object k : (List<Object>) Reflect.call(node, "getChildrenUnmodifiable")) walk(k);
        }
    }

    /** Buttons, fields, sliders, and the like. Menus and tabs are recorded by their own hooks. */
    private static boolean isInventoryControl(Object node) {
        if (Reflect.is(node, "javafx.scene.control.MenuButton")) return false;
        return Reflect.is(node, "javafx.scene.control.ButtonBase")
                || Reflect.is(node, "javafx.scene.control.ComboBoxBase")
                || is(node, "Slider") || is(node, "Spinner")
                || Reflect.is(node, "javafx.scene.control.TextInputControl");
    }

    private void note(Object node) {
        // The hidden text field inside a combo box or spinner is part of that control.
        if (node.getClass().getSimpleName().equals("FakeFocusTextField")) return;
        Object w = scanWindow != null && Reflect.is(node, "javafx.scene.control.MenuItem") ? ownerStage(scanWindow) : windowOf(node);
        t.inventory(w == null ? Views.currentPath() : homeOf(w, node), label(node), node.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private void hookMenuItems(Object menu) {
        hookItems((List<Object>) Reflect.call(menu, "getItems"));
    }

    @SuppressWarnings("unchecked")
    private void hookItems(List<Object> items) {
        if (items == null) return;
        for (Object item : items) {
            if (Reflect.is(item, "javafx.scene.control.Menu")) {
                hookItems((List<Object>) Reflect.call(item, "getItems"));
            } else if (!Reflect.is(item, "javafx.scene.control.SeparatorMenuItem") && hooked.put(item, Boolean.TRUE) == null) {
                Reflect.call(item, "addEventHandler", new Class<?>[] { Reflect.cls("javafx.event.EventType"), Reflect.cls("javafx.event.EventHandler") },
                        Reflect.staticField("javafx.event.ActionEvent", "ACTION"), actionHandler);
                note(item);
            }
        }
    }

    private void hookTabPane(final Object tabPane) {
        if (hooked.put(tabPane, Boolean.TRUE) != null) return;
        Object model = Reflect.call(tabPane, "getSelectionModel");
        Object prop = Reflect.call(model, "selectedItemProperty");
        Object listener = Reflect.proxy("javafx.beans.value.ChangeListener", new Reflect.Handler() {
            protected Object handle(String m, Object[] a, Method mm) {
                if (m.equals("changed")) tabChanged(tabPane, a[2]);
                return null;
            }
        });
        Reflect.call(prop, "addListener", new Class<?>[] { Reflect.cls("javafx.beans.value.ChangeListener") }, listener);
        Object tabs = Reflect.call(tabPane, "getTabs");
        if (tabs instanceof List) {
            for (Object tab : (List<?>) tabs) {
                Object w = windowOf(tabPane);
                t.inventory(w == null ? Views.currentPath() : homeOf(w, tabPane), mnemonicFree((String) Reflect.call(tab, "getText")), "TabPane");
            }
        }
    }

    private void windowShown(final Object w) {
        if (!Reflect.is(w, "javafx.stage.Stage")) return;
        Views.activate(t, w, pathOf(w, null));
        if (Boolean.TRUE.equals(Reflect.call(w, "isFocused"))) t.focus(true);
        Object focused = Reflect.call(w, "focusedProperty");
        Reflect.call(focused, "addListener", new Class<?>[] { Reflect.cls("javafx.beans.value.ChangeListener") },
                Reflect.proxy("javafx.beans.value.ChangeListener", new Reflect.Handler() {
                    protected Object handle(String m, Object[] a, Method mm) {
                        if (!m.equals("changed")) return null;
                        boolean on = Boolean.TRUE.equals(a[2]);
                        if (on && Views.activeWindow != w) Views.activate(t, w, pathOf(w, null));
                        t.focus(on || focusedStage() != null);
                        return null;
                    }
                }));
        Object showing = Reflect.call(w, "showingProperty");
        Reflect.call(showing, "addListener", new Class<?>[] { Reflect.cls("javafx.beans.value.ChangeListener") },
                Reflect.proxy("javafx.beans.value.ChangeListener", new Reflect.Handler() {
                    protected Object handle(String m, Object[] a, Method mm) {
                        if (m.equals("changed") && Boolean.FALSE.equals(a[2])) {
                            shownWindows.remove(w);
                            Views.closed(t, w);
                        }
                        return null;
                    }
                }));
    }

    // ---- events (FX thread) -------------------------------------------------------------------

    private void onAction(Object event) {
        if (event == lastEvent) return;        // a menu item hooked directly and seen again by the scene filter
        lastEvent = event;
        Object target = Reflect.call(event, "getTarget");
        if (target == null) return;
        // Opening a menu (MenuBar entries are MenuButtons underneath) is navigation, not a feature.
        if (Reflect.is(target, "javafx.scene.control.MenuButton")) return;
        String type;
        if (is(target, "CheckBox") || is(target, "RadioButton") || is(target, "ToggleButton")
                || is(target, "CheckMenuItem") || is(target, "RadioMenuItem") || Reflect.is(target, "javafx.scene.control.ComboBoxBase")) type = "change";
        else if (Reflect.is(target, "javafx.scene.control.ButtonBase") || Reflect.is(target, "javafx.scene.control.MenuItem")) type = "click";
        else if (Reflect.is(target, "javafx.scene.control.TextField")) type = "submit";
        else return;
        interaction(type, target);
    }

    private void onClick(Object event, boolean release) {
        Object target = Reflect.call(event, "getTarget");
        if (!Reflect.is(target, "javafx.scene.Node")) return;
        for (Object n = target; n != null; n = Reflect.call(n, "getParent")) {
            if (is(n, "Slider")) {
                if (release) interaction("change", n);
                return;
            }
            if (release) continue;
            if (Reflect.is(n, "javafx.scene.control.ButtonBase") || Reflect.is(n, "javafx.scene.control.ComboBoxBase")
                    || Reflect.is(n, "javafx.scene.control.TextInputControl") || is(n, "MenuBar") || is(n, "TabPane")
                    || is(n, "ScrollBar") || is(n, "Label")) return;
            if (is(n, "Spinner") || is(n, "ChoiceBox")) { interaction("change", n); return; }
            if (is(n, "TableView") || is(n, "ListView") || is(n, "TreeView") || is(n, "TreeTableView")) { interaction("click", n); return; }
            String cls = n.getClass().getName();
            boolean standard = cls.startsWith("javafx.") || cls.startsWith("com.sun.") || cls.startsWith("jdk.");
            if (!standard || is(n, "Canvas")) { interaction("click", n); return; }
        }
    }

    private void tabChanged(Object tabPane, Object tab) {
        Object scene = Reflect.call(tabPane, "getScene");
        Object w = Reflect.call(scene, "getWindow");
        if (w == null || !Boolean.TRUE.equals(Reflect.call(w, "isShowing"))) return;
        String path = pathOf(w, tabPane);
        String title = mnemonicFree((String) Reflect.call(tab, "getText"));
        String home = homeOf(w, tabPane);
        t.inventory(home, title, "TabPane");
        track(t.interaction("click", "javafx", path, home, title, "TabPane", xpath(tabPane), id(tabPane), ""));
        Views.routeChange(t, w, path);
    }

    /**
     * Ends the action once the app's own handlers have run. Our filter fires before them, and a
     * runLater queued from it runs after the event has been handled, or as soon as a dialog the
     * handler opened starts taking events.
     */
    private static void track(final Telemetry.Action a) {
        if (a == null) return;
        boolean queued = runLater(new Runnable() {
            public void run() { a.finish(System.nanoTime()); }
        });
        if (!queued) a.finish(System.nanoTime());
    }

    private static boolean is(Object o, String controlName) {
        return Reflect.is(o, "javafx.scene.control." + controlName) || (controlName.equals("Canvas") && Reflect.is(o, "javafx.scene.canvas.Canvas"));
    }

    // ---- naming -------------------------------------------------------------------------------

    private void interaction(String type, Object target) {
        Object w = windowOf(target);
        String path = w == null ? Views.currentPath() : pathOf(w, target);
        track(t.interaction(type, "javafx", path, w == null ? path : homeOf(w, target), label(target), target.getClass().getSimpleName(), xpath(target), id(target), ""));
    }

    private static String id(Object n) {
        Object id = Reflect.call(n, "getId");
        return id == null ? "" : String.valueOf(id);
    }

    String label(Object n) {
        String s = null;
        if (Reflect.is(n, "javafx.scene.control.Labeled") || Reflect.is(n, "javafx.scene.control.MenuItem")) s = mnemonicFree((String) Reflect.call(n, "getText"));
        if (SwingHook.empty(s) && !maskValues && (is(n, "TableView") || is(n, "ListView"))) {
            Object item = Reflect.call(Reflect.call(n, "getSelectionModel"), "getSelectedItem");
            if (item != null) s = String.valueOf(item);
        }
        if (SwingHook.empty(s)) {
            Object tip = Reflect.call(n, "getTooltip");
            if (tip != null) s = (String) Reflect.call(tip, "getText");
        }
        if (SwingHook.empty(s)) {
            Object acc = Reflect.call(n, "getAccessibleText");
            if (acc != null) s = String.valueOf(acc);
        }
        if (SwingHook.empty(s)) {
            // The prompt is a label; the typed text never is.
            Object prompt = Reflect.call(n, "getPromptText");
            if (prompt != null) s = String.valueOf(prompt);
        }
        if (SwingHook.empty(s)) s = id(n);
        if (SwingHook.empty(s) && !Reflect.is(n, "javafx.scene.control.ButtonBase")) s = precedingLabel(n);
        if (SwingHook.empty(s)) return "";
        s = s.trim();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    /** "Mode" for the combo box that follows a Label("Mode") in the same box. */
    @SuppressWarnings("unchecked")
    static String precedingLabel(Object n) {
        Object parent = Reflect.call(n, "getParent");
        List<Object> kids = parent == null ? null : (List<Object>) Reflect.call(parent, "getChildrenUnmodifiable");
        if (kids == null) return null;
        int me = kids.indexOf(n);
        for (int i = me - 1, looked = 0; i >= 0 && looked < 3; i--, looked++) {
            if (is(kids.get(i), "Label")) {
                String s = mnemonicFree((String) Reflect.call(kids.get(i), "getText"));
                if (!SwingHook.empty(s)) return s.trim().replaceAll(":$", "").trim();
            }
        }
        return null;
    }

    /** "_File" shows as "File"; "__" is a literal underscore. */
    static String mnemonicFree(String s) {
        if (s == null) return null;
        return s.replace("__", "\u0000").replace("_", "").replace('\u0000', '_');
    }

    private static Object windowOf(Object target) {
        if (Reflect.is(target, "javafx.scene.control.MenuItem")) {
            Object popup = Reflect.call(target, "getParentPopup");
            Object owner = Reflect.call(popup, "getOwnerWindow");
            if (owner != null) return ownerStage(owner);
            Object menu = Reflect.call(target, "getParentMenu");
            while (menu != null && Reflect.call(menu, "getParentMenu") != null) menu = Reflect.call(menu, "getParentMenu");
            if (menu != null) {
                Object p = Reflect.call(menu, "getParentPopup");
                Object o = Reflect.call(p, "getOwnerWindow");
                if (o != null) return ownerStage(o);
            }
            // A menu bar item with no popup chain: whatever stage is focused.
            return focusedStage();
        }
        Object scene = Reflect.call(target, "getScene");
        Object w = Reflect.call(scene, "getWindow");
        return w == null ? null : ownerStage(w);
    }

    /** Popups belong to the window that opened them. */
    private static Object ownerStage(Object w) {
        int guard = 0;
        while (w != null && Reflect.is(w, "javafx.stage.PopupWindow") && guard++ < 10) w = Reflect.call(w, "getOwnerWindow");
        return w;
    }

    @SuppressWarnings("unchecked")
    private static Object focusedStage() {
        Object list = Reflect.callStatic("javafx.stage.Window", "getWindows", new Class<?>[0]);
        if (list == null) list = Reflect.callStatic("com.sun.javafx.stage.StageHelper", "getStages", new Class<?>[0]);
        if (!(list instanceof List)) return null;
        for (Object w : (List<Object>) list) if (Boolean.TRUE.equals(Reflect.call(w, "isFocused"))) return w;
        return null;
    }

    /** "/Stage title/Selected tab", or "/Owner title/Stage title" for owned stages and dialogs. */
    static String pathOf(Object w, Object near) {
        String title = (String) Reflect.call(w, "getTitle");
        Object owner = Reflect.call(w, "getOwner");
        if (owner != null) {
            String ot = (String) Reflect.call(owner, "getTitle");
            if (!SwingHook.empty(ot)) return Views.path(ot, title);
        }
        Object tabs = null;
        for (Object n = near; n != null && tabs == null; n = Reflect.call(n, "getParent")) if (is(n, "TabPane")) tabs = n;
        if (tabs == null) {
            Object scene = Reflect.call(w, "getScene");
            Object root = Reflect.call(scene, "getRoot");
            Set<?> found = (Set<?>) Reflect.call(root, "lookupAll", new Class<?>[] { String.class }, ".tab-pane");
            if (found != null && !found.isEmpty()) tabs = found.iterator().next();
        }
        String tab = null;
        if (tabs != null) {
            Object sel = Reflect.call(Reflect.call(tabs, "getSelectionModel"), "getSelectedItem");
            if (sel != null) tab = mnemonicFree((String) Reflect.call(sel, "getText"));
        }
        return Views.path(title, tab);
    }

    /**
     * Where a control lives, whichever tab happens to be showing: "/Stage title/Tab that contains it",
     * or "/Stage title" for menus, toolbars, and anything else outside the tabs. An owned stage is its
     * own page, as in pathOf.
     */
    static String homeOf(Object w, Object node) {
        String title = (String) Reflect.call(w, "getTitle");
        Object owner = Reflect.call(w, "getOwner");
        if (owner != null) {
            String ot = (String) Reflect.call(owner, "getTitle");
            if (!SwingHook.empty(ot)) return Views.path(ot, title);
        }
        // Menu items are not in the scene graph; they live on the window.
        if (node == null || Reflect.is(node, "javafx.scene.control.MenuItem")) return Views.path(title, null);
        List<Object> below = new ArrayList<Object>();
        for (Object n = node; n != null && below.size() < 60; n = Reflect.call(n, "getParent")) {
            Object tabs = is(n, "TabPane") && !below.isEmpty() ? Reflect.call(n, "getTabs") : null;
            if (tabs instanceof List) {
                for (Object tab : (List<?>) tabs) {
                    Object content = Reflect.call(tab, "getContent");
                    if (content != null && below.contains(content)) return Views.path(title, mnemonicFree((String) Reflect.call(tab, "getText")));
                }
            }
            below.add(n);
        }
        return Views.path(title, null);
    }

    @SuppressWarnings("unchecked")
    static String xpath(Object n) {
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (Object p = n; p != null && depth < 40; p = Reflect.call(p, "getParent"), depth++) {
            String name = p.getClass().getSimpleName();
            Object parent = Reflect.call(p, "getParent");
            int idx = 0;
            if (parent != null) {
                List<Object> kids = (List<Object>) Reflect.call(parent, "getChildrenUnmodifiable");
                if (kids != null) {
                    int c = 0;
                    for (Object k : kids) {
                        if (k.getClass() == p.getClass()) {
                            c++;
                            if (k == p) { idx = c; break; }
                        }
                    }
                }
            }
            String part = idx > 0 ? name + "[" + idx + "]" : name;
            b.insert(0, b.length() == 0 ? part : part + "/");
        }
        return b.length() > 300 ? b.substring(b.length() - 300) : b.toString();
    }
}
