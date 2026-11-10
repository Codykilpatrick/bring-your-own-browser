package poc.uiagent;

import java.awt.AWTEvent;
import java.awt.Button;
import java.awt.Checkbox;
import java.awt.CheckboxMenuItem;
import java.awt.Choice;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.TextField;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ContainerEvent;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.text.JTextComponent;

/**
 * Swing and AWT without touching the app: one AWTEventListener on the Toolkit sees every container
 * change, mouse click, and window event. Buttons, menu items, combos, sliders, spinners, and text
 * fields get a listener added as they appear (so keyboard and mnemonic use count too); everything
 * else (tables, lists, trees, custom components) is credited from the mouse click itself.
 */
public final class SwingHook implements AWTEventListener {
    private static final Object MARK = Boolean.TRUE;
    private final Telemetry t;
    private final boolean maskValues;
    private final Map<Object, Object> hooked = Collections.synchronizedMap(new WeakHashMap<Object, Object>());
    private final Map<Window, Object> openedWindows = Collections.synchronizedMap(new WeakHashMap<Window, Object>());
    /** Controls already written to the inventory. */
    private final Map<Object, Object> noted = Collections.synchronizedMap(new WeakHashMap<Object, Object>());

    public SwingHook(Telemetry t) {
        this.t = t;
        this.maskValues = !"allow".equals(t.cfg.privacy);
    }

    public void install() {
        try {
            UserEventQueue.hook = this;
            Toolkit.getDefaultToolkit().addAWTEventListener(this,
                    AWTEvent.CONTAINER_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                            | AWTEvent.KEY_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
            Toolkit.getDefaultToolkit().getSystemEventQueue().push(new UserEventQueue());
            syncFocus();
        } catch (Throwable e) {
            Agent.info("swing hook not installed: " + e);
            return;
        }
        // A late attach (agentmain) finds windows already open.
        if (Window.getWindows().length > 0) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    for (Window w : Window.getWindows()) {
                        hookTree(w);
                        if (w.isShowing() && isPage(w)) {
                            openedWindows.put(w, MARK);
                            if (w.isActive() || Views.activeWindow == null) Views.activate(t, w, pathOf(w, null));
                        }
                    }
                }
            });
        }
        Agent.debug("swing hook installed");
    }

    /** The pushed UserEventQueue stays; without the hook it only keeps its own bookkeeping. */
    public void stop() {
        if (UserEventQueue.hook == this) UserEventQueue.hook = null;
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
    }

    // ---- toolkit events -----------------------------------------------------------------------

    public void eventDispatched(AWTEvent e) {
        try {
            if (e instanceof InputEvent) t.touch();
            switch (e.getID()) {
                case ContainerEvent.COMPONENT_ADDED:
                    hookTree(((ContainerEvent) e).getChild());
                    break;
                case WindowEvent.WINDOW_OPENED: {
                    Window w = ((WindowEvent) e).getWindow();
                    hookTree(w);
                    if (isPage(w)) {
                        openedWindows.put(w, MARK);
                        Views.activate(t, w, pathOf(w, null));
                    }
                    break;
                }
                case WindowEvent.WINDOW_ACTIVATED: {
                    Window w = ((WindowEvent) e).getWindow();
                    syncFocus();
                    // A window hidden and shown again gets no WINDOW_OPENED; pick up what was added meanwhile.
                    hookTree(w);
                    if (isPage(w) && openedWindows.containsKey(w) && w != Views.activeWindow) Views.activate(t, w, pathOf(w, null));
                    break;
                }
                case WindowEvent.WINDOW_DEACTIVATED:
                    syncFocus();
                    break;
                case WindowEvent.WINDOW_CLOSED: {
                    Window w = ((WindowEvent) e).getWindow();
                    openedWindows.remove(w);
                    Views.closed(t, w);
                    break;
                }
                case MouseEvent.MOUSE_CLICKED:
                    mouseClicked((MouseEvent) e);
                    break;
                default:
            }
        } catch (Throwable ex) {
            Agent.debug("swing hook: " + ex);
        }
    }

    /** A page window in front means someone is at the console. A menu popup does not count as leaving. */
    private void syncFocus() {
        Window active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        t.focus(active != null && isPage(active));
    }

    /** Frames and dialogs are pages. Popups, tooltips, and bare JWindows are not. */
    static boolean isPage(Window w) {
        if (w instanceof JWindow || !(w instanceof Frame || w instanceof Dialog)) return false;
        return !w.getClass().getName().contains("Popup");
    }

    // ---- listener injection -------------------------------------------------------------------

    void hookTree(Component c) {
        if (c == null) return;
        hookOne(c);
        if (hooked.containsKey(c)) note(c);
        if (c instanceof JMenu) {
            for (Component m : ((JMenu) c).getMenuComponents()) hookTree(m);
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) hookTree(k);
        }
        if (c instanceof Frame) hookMenuBar(((Frame) c).getMenuBar());
    }

    private boolean mark(Object o) {
        synchronized (hooked) {
            if (hooked.containsKey(o)) return false;
            hooked.put(o, MARK);
            return true;
        }
    }

    private void hookOne(final Component c) {
        if (c instanceof AbstractButton && !(c instanceof JMenu)) {
            if (isLookAndFeelPart(c)) return;
            if (!mark(c)) return;
            ((AbstractButton) c).addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    if (!fromUser()) return;
                    if (c instanceof javax.swing.JToggleButton || c instanceof javax.swing.JCheckBoxMenuItem
                            || c instanceof javax.swing.JRadioButtonMenuItem) interaction("change", c);
                    else interaction("click", c);
                }
            });        } else if (c instanceof JTabbedPane) {
            if (!mark(c)) return;
            final JTabbedPane tabs = (JTabbedPane) c;
            tabs.addChangeListener(new ChangeListener() {
                public void stateChanged(ChangeEvent e) { tabChanged(tabs); }
            });
        } else if (c instanceof JComboBox) {
            if (!mark(c)) return;
            ((JComboBox) c).addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { if (fromUser()) interaction("change", c); }
            });        } else if (c instanceof JSlider) {
            if (!mark(c)) return;
            ((JSlider) c).addChangeListener(new ChangeListener() {
                public void stateChanged(ChangeEvent e) {
                    if (!((JSlider) c).getValueIsAdjusting() && fromUser()) interaction("change", c);
                }
            });        } else if (c instanceof JSpinner) {
            if (!mark(c)) return;
            ((JSpinner) c).addChangeListener(new ChangeListener() {
                public void stateChanged(ChangeEvent e) { if (fromUser()) interaction("change", c); }
            });        } else if (c instanceof javax.swing.JTextField) {
            if (!mark(c)) return;
            ((javax.swing.JTextField) c).addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { if (fromUser()) interaction("submit", c); }
            });        } else if (c instanceof Button) {
            if (!mark(c)) return;
            ((Button) c).addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { if (fromUser()) interaction("click", c); }
            });        } else if (c instanceof Checkbox) {
            if (!mark(c)) return;
            ((Checkbox) c).addItemListener(new ItemListener() {
                public void itemStateChanged(ItemEvent e) { if (fromUser()) interaction("change", c); }
            });        } else if (c instanceof Choice) {
            if (!mark(c)) return;
            ((Choice) c).addItemListener(new ItemListener() {
                public void itemStateChanged(ItemEvent e) { if (fromUser()) interaction("change", c); }
            });        } else if (c instanceof TextField) {
            if (!mark(c)) return;
            ((TextField) c).addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { if (fromUser()) interaction("submit", c); }
            });        } else if (c instanceof java.awt.List) {
            if (!mark(c)) return;
            ((java.awt.List) c).addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { if (fromUser()) interaction("click", c); }
            });        }
    }

    private void hookMenuBar(MenuBar bar) {
        if (bar == null) return;
        for (int i = 0; i < bar.getMenuCount(); i++) hookMenu(bar.getMenu(i), bar);
        if (bar.getHelpMenu() != null) hookMenu(bar.getHelpMenu(), bar);
    }

    private void hookMenu(Menu menu, final MenuBar bar) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            final MenuItem item = menu.getItem(i);
            if (item instanceof Menu) hookMenu((Menu) item, bar);
            else if (item instanceof CheckboxMenuItem) {
                if (!mark(item)) continue;
                ((CheckboxMenuItem) item).addItemListener(new ItemListener() {
                    public void itemStateChanged(ItemEvent e) { menuInteraction("change", item, bar); }
                });
                noteMenu(item, bar);
            } else if (item != null && !"-".equals(item.getLabel())) {
                if (!mark(item)) continue;
                item.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) { menuInteraction("click", item, bar); }
                });
                noteMenu(item, bar);
            }
        }
    }

    private void menuInteraction(String type, MenuItem item, MenuBar bar) {
        Frame frame = null;
        for (Frame f : Frame.getFrames()) if (f.getMenuBar() == bar) frame = f;
        String path = frame == null ? Views.currentPath() : pathOf(frame, null);
        String home = frame == null ? path : homeOf(frame, null);
        UserEventQueue.track(t.interaction(type, "awt", path, home, item.getLabel(), "MenuItem", "MenuBar/Menu/MenuItem", "", ""));
    }

    /** Arrow buttons inside combos, spinners, scroll bars, and other look-and-feel internals. */
    private static boolean isLookAndFeelPart(Component c) {
        if (c.getClass().getName().startsWith("javax.swing.plaf")) return true;
        for (Component p = c.getParent(); p != null; p = p.getParent()) {
            if (p instanceof JComboBox || p instanceof JSpinner || p instanceof JScrollBar) return true;
            if (p instanceof JTabbedPane && !(p.getParent() instanceof JTabbedPane)) return false;
        }
        return false;
    }

    /**
     * Only count listener callbacks that happen while a real input event is being dispatched, so
     * timers and programmatic setSelectedItem calls don't count as use. EventQueue.getCurrentEvent is
     * not enough: hiding a menu or combo popup dispatches a ContainerEvent that replaces it before the
     * item's action fires. UserEventQueue keeps a stack of what each nested dispatch started from.
     */
    static boolean fromUser() {
        return UserEventQueue.inUserEvent();
    }

    /**
     * Pushed onto the system queue. It adds the bookkeeping for fromUser(), and it times each user
     * event from when it leaves the queue to when the app's listeners have all returned.
     */
    static final class UserEventQueue extends EventQueue {
        static SwingHook hook;

        /** One dispatchEvent call. A modal dialog pumps its events from inside the event that opened it, so these nest. */
        static final class Dispatch {
            final boolean user;
            final int id, key;
            /** The hooked control this event is aimed at, if any. */
            Component control;
            final long start = System.nanoTime();
            /** When a dialog opened from inside this event started taking events. The handler's own time ends there. */
            long nestedAt;
            boolean counted;
            java.util.ArrayList<Telemetry.Action> actions;

            Dispatch(AWTEvent e) {
                user = isUser(e);
                id = e.getID();
                key = e instanceof KeyEvent ? ((KeyEvent) e).getKeyCode() : 0;
            }

            void finish(long end) {
                if (actions == null) return;
                for (Telemetry.Action a : actions) a.finish(end);
                actions = null;
            }
        }

        private static final ThreadLocal<java.util.ArrayList<Dispatch>> STACK = new ThreadLocal<java.util.ArrayList<Dispatch>>() {
            protected java.util.ArrayList<Dispatch> initialValue() { return new java.util.ArrayList<Dispatch>(); }
        };

        static boolean inUserEvent() {
            java.util.ArrayList<Dispatch> s = STACK.get();
            return !s.isEmpty() && s.get(s.size() - 1).user;
        }

        /** Holds the action until the event being dispatched on this thread has been handled. */
        static void track(Telemetry.Action a) {
            if (a == null) return;
            java.util.ArrayList<Dispatch> s = STACK.get();
            if (s.isEmpty()) {
                a.finish(System.nanoTime());
                return;
            }
            Dispatch d = s.get(s.size() - 1);
            d.counted = true;
            a.startedAt(d.start);
            if (d.nestedAt != 0) {
                // The app's listener ran first and opened a dialog; the time the dialog stayed up is not the handler's.
                a.finish(d.nestedAt);
                return;
            }
            if (d.actions == null) d.actions = new java.util.ArrayList<Telemetry.Action>(2);
            d.actions.add(a);
        }

        static boolean isUser(AWTEvent e) {
            if (e instanceof InputEvent) return true;
            // Heavyweight AWT buttons, check boxes, and menu items post their action as its own event.
            if (e instanceof ActionEvent || e instanceof ItemEvent) {
                Object src = e.getSource();
                return src instanceof java.awt.MenuComponent || src instanceof Component && !(src instanceof JComponent) && !((Component) src).isLightweight();
            }
            return false;
        }

        protected void dispatchEvent(AWTEvent e) {
            SwingHook h = hook;
            java.util.ArrayList<Dispatch> s = STACK.get();
            Dispatch d = new Dispatch(e);
            if (h != null) d.control = h.armFrom(e);
            if (!s.isEmpty()) {
                Dispatch outer = s.get(s.size() - 1);
                if (outer.nestedAt == 0) {
                    outer.nestedAt = d.start;
                    outer.finish(d.start);
                }
            }
            s.add(d);
            try {
                super.dispatchEvent(e);
            } catch (RuntimeException ex) {
                if (h != null) h.handlerThrew(d);
                throw ex;
            } catch (Error ex) {
                if (h != null) h.handlerThrew(d);
                throw ex;
            } finally {
                s.remove(s.size() - 1);
                d.finish(d.nestedAt != 0 ? d.nestedAt : System.nanoTime());
            }
        }
    }

    /**
     * Names the control before the app's listener runs, so a stall or an exception inside that listener
     * can be blamed on it. Returns the control when it is one this hook listens to.
     */
    Component armFrom(AWTEvent e) {
        int id = e.getID();
        if (id != MouseEvent.MOUSE_PRESSED && id != MouseEvent.MOUSE_RELEASED && id != MouseEvent.MOUSE_CLICKED
                && id != KeyEvent.KEY_PRESSED && !(e instanceof ActionEvent) && !(e instanceof ItemEvent)) return null;
        Object src = e.getSource();
        if (src instanceof MenuItem) {
            t.arm(((MenuItem) src).getLabel(), "MenuItem", Views.currentPath(), Views.currentPath());
            return null;
        }
        if (!(src instanceof Component)) return null;
        Component c = (Component) src;
        // The queue hands out mouse and key events addressed to the window. Swing picks the control afterwards.
        if (e instanceof MouseEvent) {
            Component deep = SwingUtilities.getDeepestComponentAt(c, ((MouseEvent) e).getX(), ((MouseEvent) e).getY());
            if (deep != null) c = deep;
        } else if (e instanceof KeyEvent) {
            // With a menu open the key goes to the menu (the highlighted item, or a mnemonic in the open
            // list), not to whatever has the focus.
            javax.swing.MenuElement[] menu = javax.swing.MenuSelectionManager.defaultManager().getSelectedPath();
            Component to = menu.length > 0 ? menu[menu.length - 1].getComponent()
                    : java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (to instanceof JPopupMenu && ((JPopupMenu) to).getInvoker() != null) to = ((JPopupMenu) to).getInvoker();
            if (to != null) c = to;
        }
        Component control = null;
        for (Component p = c; p != null && !(p instanceof Window); p = parentOf(p)) {
            if (hooked.containsKey(p)) {
                control = p;
                break;
            }
        }
        if (control != null) {
            c = control;
        } else if (isLookAndFeelPart(c)) {
            for (Component p = c; p != null; p = parentOf(p)) {
                if (p instanceof JComboBox || p instanceof JSpinner || p instanceof JScrollBar || p instanceof AbstractButton) {
                    c = p;
                    break;
                }
            }
        }
        Window w = windowOf(c);
        String path = w == null ? Views.currentPath() : pathOf(w, c);
        t.arm(label(c), element(c), path, w == null ? path : homeOf(w, c));
        return control instanceof JTabbedPane ? null : control;
    }

    /**
     * The app's own listener threw while a user event was being handled. The exception row that follows
     * names the control only when an action was recorded for this event; otherwise it would be a guess.
     */
    void handlerThrew(UserEventQueue.Dispatch d) {
        if (!d.user) return;
        Component c = d.control;
        // Listeners run last added first. When the app's ran before ours and threw, ours never ran, so
        // count the use here. A mouse press alone does not activate a control, and neither does a key
        // other than Enter unless the control is a button or a menu item.
        boolean activates = d.id != MouseEvent.MOUSE_PRESSED
                && (d.id != KeyEvent.KEY_PRESSED || d.key == KeyEvent.VK_ENTER || c instanceof AbstractButton);
        if (c != null && !d.counted && activates) {
            String type = c instanceof javax.swing.JToggleButton || c instanceof javax.swing.JCheckBoxMenuItem || c instanceof javax.swing.JRadioButtonMenuItem ? "change"
                    : c instanceof AbstractButton || c instanceof Button || c instanceof java.awt.List ? "click"
                    : c instanceof javax.swing.JTextField || c instanceof TextField ? "submit" : "change";
            interaction(type, c);
        }
        if (d.counted) t.threw();
    }

    static String element(Component c) {
        String el = c.getClass().getSimpleName();
        return el.length() == 0 ? c.getClass().getSuperclass().getSimpleName() : el;
    }

    /** Writes a hooked control to the inventory the first time it is seen inside a window that is showing. */
    private void note(Component c) {
        Window w = windowOf(c);
        if (w == null || !w.isShowing()) return;
        if (c instanceof JTabbedPane) {
            noteTabs((JTabbedPane) c);
            return;
        }
        if (noted.put(c, MARK) != null) return;
        // The text field inside a spinner or an editable combo, and a table's cell editor, are parts of
        // those controls, not controls a user would look for.
        if (c instanceof JTextComponent) {
            for (Component p = c.getParent(); p != null && !(p instanceof Window); p = p.getParent()) {
                if (p instanceof JSpinner || p instanceof JComboBox || p instanceof JTable || p instanceof JTree) return;
            }
        }
        t.inventory(homeOf(w, c), label(c), element(c));
    }

    private void noteTabs(JTabbedPane tabs) {
        Window w = windowOf(tabs);
        if (w == null) return;
        String home = homeOf(w, tabs);
        for (int i = 0; i < tabs.getTabCount(); i++) t.inventory(home, plain(tabs.getTitleAt(i)), "JTabbedPane");
    }

    private void noteMenu(MenuItem item, MenuBar bar) {
        Frame frame = null;
        for (Frame f : Frame.getFrames()) if (f.getMenuBar() == bar) frame = f;
        t.inventory(frame == null ? Views.currentPath() : homeOf(frame, null), item.getLabel(), "MenuItem");
    }

    private void tabChanged(JTabbedPane tabs) {
        Window w = SwingUtilities.getWindowAncestor(tabs);
        if (w == null || !w.isShowing()) return;
        int i = tabs.getSelectedIndex();
        String title = i >= 0 ? plain(tabs.getTitleAt(i)) : null;
        String path = pathOf(w, tabs);
        if (fromUser()) {
            UserEventQueue.track(t.interaction("click", "swing", path, homeOf(w, tabs), title, "JTabbedPane", xpath(tabs), "", tabs.getName()));
        }
        noteTabs(tabs);
        Views.routeChange(t, w, path);
    }

    // ---- mouse clicks on everything else ------------------------------------------------------

    private void mouseClicked(MouseEvent e) {
        Component src = e.getComponent();
        // The heavyweight window's own copy of the click never reaches toolkit listeners once Swing has
        // retargeted it to the lightweight component under the pointer, so each click arrives once.
        if (src == null) return;
        Component c = SwingUtilities.getDeepestComponentAt(src, e.getX(), e.getY());
        if (c == null) c = src;
        Component target = mouseTarget(c);
        if (target == null) return;
        interaction("click", target);
    }

    /** The component a click should be credited to, or null when a listener already covers it. */
    private Component mouseTarget(Component c) {
        for (Component p = c; p != null; p = parentOf(p)) {
            if (hooked.containsKey(p)) return null;                 // buttons, combos, sliders, text fields
            if (p instanceof JPopupMenu && ((JPopupMenu) p).getInvoker() instanceof JComboBox) return null;
            if (p instanceof JMenu || p instanceof JScrollBar || p instanceof JTabbedPane) return null;
            if (p instanceof JTextComponent || p instanceof java.awt.TextComponent) return null;
            if (p instanceof JTable || p instanceof JList || p instanceof JTree) return p;
            if (p instanceof JLabel) return null;
            if (p instanceof Window) return null;
            String cls = p.getClass().getName();
            boolean standard = cls.startsWith("javax.swing.") || cls.startsWith("java.awt.") || cls.startsWith("sun.") || cls.startsWith("com.sun.");
            if (!standard) return p;                                 // a custom component
            if (p instanceof java.awt.Canvas) return p;
        }
        return null;
    }

    // ---- naming -------------------------------------------------------------------------------

    private void interaction(String type, Component c) {
        Window w = windowOf(c);
        String path = w == null ? Views.currentPath() : pathOf(w, c);
        String name = c.getName();
        UserEventQueue.track(t.interaction(type, "swing", path, w == null ? path : homeOf(w, c), label(c), element(c), xpath(c), "", name == null ? "" : name));
    }

    /** A human name for the control. Never the value typed or the row's data under mask-values. */
    String label(Component c) {
        String s = null;
        if (c instanceof AbstractButton) s = plain(((AbstractButton) c).getText());
        else if (c instanceof Button) s = ((Button) c).getLabel();
        else if (c instanceof Checkbox) s = ((Checkbox) c).getLabel();
        else if (c instanceof JTable && !maskValues) {
            JTable tbl = (JTable) c;
            int r = tbl.getSelectedRow();
            if (r >= 0 && tbl.getColumnCount() > 0) s = String.valueOf(tbl.getValueAt(r, 0));
        } else if (c instanceof JList && !maskValues) {
            Object v = ((JList) c).getSelectedValue();
            if (v != null) s = String.valueOf(v);
        }
        if (empty(s) && c instanceof JComponent) {
            Object by = ((JComponent) c).getClientProperty("labeledBy");
            if (by instanceof JLabel) s = plain(((JLabel) by).getText());
            if (empty(s)) s = ((JComponent) c).getToolTipText();
        }
        if (empty(s)) {
            try {
                javax.accessibility.AccessibleContext ac = c.getAccessibleContext();
                if (ac != null) s = ac.getAccessibleName();
            } catch (Throwable ignored) { }
            // Accessible names fall back to the text field's contents; never record those.
            if (c instanceof JTextComponent || c instanceof java.awt.TextComponent) s = null;
        }
        if (empty(s)) s = c.getName();
        if (empty(s) && !(c instanceof AbstractButton)) s = precedingLabel(c);
        if (empty(s)) return "";
        s = s.trim();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    /** "Gain" for the slider that follows a JLabel("Gain"), the usual label-then-field layout. */
    static String precedingLabel(Component c) {
        Container parent = c.getParent();
        if (parent == null) return null;
        Component[] kids = parent.getComponents();
        int me = -1;
        for (int i = 0; i < kids.length; i++) if (kids[i] == c) me = i;
        for (int i = me - 1, looked = 0; i >= 0 && looked < 3; i--, looked++) {
            if (kids[i] instanceof JLabel) {
                String s = plain(((JLabel) kids[i]).getText());
                if (!empty(s)) return s.trim().replaceAll(":$", "").trim();
            }
            if (kids[i] instanceof javax.swing.Box.Filler) looked--;
        }
        return null;
    }

    static boolean empty(String s) { return s == null || s.trim().length() == 0; }

    static String plain(String s) {
        if (s == null) return null;
        if (s.regionMatches(true, 0, "<html>", 0, 6)) s = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        return s;
    }

    static Window windowOf(Component c) {
        for (Component p = c; p != null; p = parentOf(p)) if (p instanceof Window) return (Window) p;
        return null;
    }

    /** Parent, following popup menus back to the component that opened them. */
    static Component parentOf(Component c) {
        if (c instanceof JPopupMenu) {
            Component inv = ((JPopupMenu) c).getInvoker();
            if (inv != null) return inv;
        }
        return c.getParent();
    }

    static String title(Window w) {
        if (w instanceof Frame) return ((Frame) w).getTitle();
        if (w instanceof Dialog) return ((Dialog) w).getTitle();
        return null;
    }

    /** "/Frame title/Selected tab", or "/Owner title/Dialog title" for dialogs. */
    static String pathOf(Window w, Component near) {
        if (w instanceof Dialog) {
            Window owner = w.getOwner();
            while (owner != null && !(owner instanceof Frame || owner instanceof Dialog && !empty(title(owner)))) owner = owner.getOwner();
            if (owner != null && !empty(title(owner))) return Views.path(title(owner), title(w));
            return Views.path(title(w), null);
        }
        JTabbedPane tabs = null;
        for (Component p = near; p != null && tabs == null; p = parentOf(p)) if (p instanceof JTabbedPane) tabs = (JTabbedPane) p;
        if (tabs == null) tabs = findTabs(w);
        String tab = null;
        if (tabs != null && tabs.getSelectedIndex() >= 0) tab = tabs.getTitleAt(tabs.getSelectedIndex());
        return Views.path(title(w), tab);
    }

    /**
     * Where a control lives, whichever tab happens to be showing: "/Frame title/Tab that contains it",
     * or "/Frame title" for menus, toolbars, and anything else outside the tabs. A dialog is its own
     * page. The inventory and each use are filed under this, so the two can be compared.
     */
    static String homeOf(Window w, Component c) {
        if (w instanceof Dialog) return pathOf(w, c);
        for (Component p = c; p != null && !(p instanceof Window); p = parentOf(p)) {
            Component parent = parentOf(p);
            if (!(parent instanceof JTabbedPane)) continue;
            int i = ((JTabbedPane) parent).indexOfComponent(p);
            if (i >= 0) return Views.path(title(w), ((JTabbedPane) parent).getTitleAt(i));
        }
        return Views.path(title(w), null);
    }

    static JTabbedPane findTabs(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof JTabbedPane && k.isShowing()) return (JTabbedPane) k;
            if (k instanceof Container) {
                JTabbedPane f = findTabs((Container) k);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** Class names from the window down with a sibling index, like "JFrame/JRootPane/.../JButton[2]". */
    static String xpath(Component c) {
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (Component p = c; p != null && depth < 40; p = parentOf(p), depth++) {
            String name = p.getClass().getSimpleName();
            if (name.length() == 0) name = p.getClass().getSuperclass().getSimpleName();
            Component parent = parentOf(p);
            int idx = 0;
            if (parent instanceof Container) {
                Component[] kids = ((Container) parent).getComponents();
                for (int i = 0, n = 0; i < kids.length; i++) {
                    if (kids[i].getClass() == p.getClass()) {
                        n++;
                        if (kids[i] == p) { idx = n; break; }
                    }
                }
            }
            String part = idx > 0 ? name + "[" + idx + "]" : name;
            b.insert(0, b.length() == 0 ? part : part + "/");
            if (p instanceof Window) break;
        }
        return b.length() > 300 ? b.substring(b.length() - 300) : b.toString();
    }
}
