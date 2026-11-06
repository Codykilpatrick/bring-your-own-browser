package demo.sonar;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;

/**
 * Sonar Console: a deliberately rough legacy Swing app (Java 6 source). Icon-only buttons with no
 * names, anonymous listeners, an AWT calibration window, modal dialogs, a button that blocks the
 * event thread, an uncaught exception on the event thread, log4j 1.2 and System.out logging, and a
 * contact history that only grows.
 */
public class SonarConsole {
    private static final Logger LOG = Logger.getLogger(SonarConsole.class);
    static final Random RND = new Random(42);
    // Never trimmed: the heap climbs for as long as the app runs.
    static final List<byte[]> HISTORY = new ArrayList<byte[]>();

    final JFrame frame = new JFrame("Sonar Console");
    final JTabbedPane tabs = new JTabbedPane();
    final DefaultTableModel contacts = new DefaultTableModel(new Object[] {"Contact", "Bearing", "Range", "Class"}, 0);
    final JTable contactTable = new JTable(contacts);
    final Waterfall waterfall = new Waterfall();
    final DefaultListModel historyModel = new DefaultListModel();
    final JLabel status = new JLabel("Ready");
    JButton zoomIn, zoomOut, mark, recompute, clearHistory;
    JCheckBox autoTrack;
    JComboBox band;
    JSlider gain;
    JSpinner averaging;
    JTextField bearing;
    Calibration calibration;

    public static void main(String[] args) {
        BasicConfigurator.configure();
        LOG.info("Sonar Console starting on Java " + System.getProperty("java.version"));
        System.out.println("stdout: console boot");
        SwingUtilities.invokeLater(new Runnable() {
            public void run() { new SonarConsole().show(); }
        });
    }

    void show() {
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setJMenuBar(menuBar());
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(toolBar(), BorderLayout.NORTH);
        frame.getContentPane().add(tabs, BorderLayout.CENTER);
        frame.getContentPane().add(statusBar(), BorderLayout.SOUTH);

        tabs.addTab("Broadband", broadbandPanel());
        tabs.addTab("Narrowband", narrowbandPanel());
        tabs.addTab("History", historyPanel());
        tabs.setMnemonicAt(0, KeyEvent.VK_B);
        tabs.setMnemonicAt(1, KeyEvent.VK_N);
        tabs.setMnemonicAt(2, KeyEvent.VK_Y);

        for (int i = 0; i < 12; i++) addContact();

        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { exit(); }
        });
        frame.setSize(1024, 768);
        frame.setLocation(0, 0);
        frame.setVisible(true);
        LOG.info("main window shown");

        new Timer(1000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                HISTORY.add(new byte[100 * 1024]);
                if (RND.nextInt(4) == 0) {
                    addContact();
                    if (autoTrack.isSelected()) LOG.debug("auto track picked up contact " + contacts.getRowCount());
                }
                waterfall.repaint();
            }
        }).start();
        new Timer(1000, new ActionListener() {
            public void actionPerformed(ActionEvent e) { dumpBounds(); }
        }).start();
    }

    JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.setMnemonic(KeyEvent.VK_F);
        file.add(item("Export Contacts...", KeyEvent.VK_E, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                LOG.info("exported " + contacts.getRowCount() + " contacts");
                JOptionPane.showMessageDialog(frame, contacts.getRowCount() + " contacts exported.", "Export", JOptionPane.INFORMATION_MESSAGE);
            }
        }));
        file.add(item("Print Summary", KeyEvent.VK_P, new ActionListener() {
            public void actionPerformed(ActionEvent e) { status.setText("Printing..."); }
        }));
        file.addSeparator();
        file.add(item("Exit", KeyEvent.VK_X, new ActionListener() {
            public void actionPerformed(ActionEvent e) { exit(); }
        }));
        JMenu tools = new JMenu("Tools");
        tools.setMnemonic(KeyEvent.VK_T);
        tools.add(item("Recompute Bearings", KeyEvent.VK_R, new ActionListener() {
            public void actionPerformed(ActionEvent e) { recompute(); }
        }));
        tools.add(item("Calibrate...", KeyEvent.VK_C, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (calibration == null) calibration = new Calibration();
                calibration.setVisible(true);
            }
        }));
        tools.add(item("Self Test", KeyEvent.VK_S, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                LOG.warn("self test requested");
                throw new IllegalStateException("Self test failed: hydrophone array 3 not responding");
            }
        }));
        JMenu help = new JMenu("Help");
        help.setMnemonic(KeyEvent.VK_H);
        help.add(item("About", KeyEvent.VK_A, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JOptionPane.showMessageDialog(frame, "Sonar Console 2.3\nJava " + System.getProperty("java.version"), "About", JOptionPane.PLAIN_MESSAGE);
            }
        }));
        bar.add(file);
        bar.add(tools);
        bar.add(help);
        return bar;
    }

    static JMenuItem item(String text, int mnemonic, ActionListener l) {
        JMenuItem i = new JMenuItem(text);
        i.setMnemonic(mnemonic);
        i.addActionListener(l);
        return i;
    }

    JToolBar toolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        // Icon-only buttons: no text, no name, and only one has a tooltip.
        zoomIn = iconButton(Color.GREEN, "+");
        zoomOut = iconButton(Color.ORANGE, "-");
        mark = iconButton(Color.RED, "M");
        mark.setToolTipText("Mark contact");
        zoomIn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { waterfall.zoom(1); }
        });
        zoomOut.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { waterfall.zoom(-1); }
        });
        mark.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { waterfall.markCentre(); }
        });
        bar.add(zoomIn);
        bar.add(zoomOut);
        bar.add(mark);
        bar.addSeparator();
        autoTrack = new JCheckBox("Auto track");
        autoTrack.setMnemonic(KeyEvent.VK_U);
        autoTrack.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) { LOG.info("auto track " + (e.getStateChange() == ItemEvent.SELECTED ? "on" : "off")); }
        });
        bar.add(autoTrack);
        bar.addSeparator();
        bar.add(new JLabel("Band: "));
        band = new JComboBox(new String[] {"Low", "Mid", "High"});
        band.setMaximumSize(new Dimension(100, 28));
        band.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { LOG.info("band " + band.getSelectedItem()); }
        });
        bar.add(band);
        return bar;
    }

    static JButton iconButton(Color c, String glyph) {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillOval(1, 1, 18, 18);
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString(glyph, 6, 15);
        g.dispose();
        JButton b = new JButton(new ImageIcon(img));
        b.setPreferredSize(new Dimension(32, 32));
        b.setFocusable(false);
        return b;
    }

    JPanel broadbandPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(waterfall, BorderLayout.CENTER);
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createTitledBorder("Receiver"));
        side.add(new JLabel("Gain"));
        gain = new JSlider(0, 100, 40);
        gain.setMajorTickSpacing(25);
        gain.setPaintTicks(true);
        gain.setPaintLabels(true);
        gain.setPreferredSize(new Dimension(180, 60));
        gain.setMaximumSize(new Dimension(180, 60));
        side.add(gain);
        side.add(Box.createVerticalStrut(10));
        side.add(new JLabel("Averaging"));
        averaging = new JSpinner(new SpinnerNumberModel(4, 1, 64, 1));
        averaging.setMaximumSize(new Dimension(80, 28));
        side.add(averaging);
        side.add(Box.createVerticalStrut(10));
        JLabel bl = new JLabel("Bearing");
        bearing = new JTextField(8);
        bl.setLabelFor(bearing);
        bearing.setMaximumSize(new Dimension(120, 28));
        bearing.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                try {
                    waterfall.cursor = Integer.parseInt(bearing.getText().trim());
                    status.setText("Cursor at " + waterfall.cursor);
                } catch (NumberFormatException ex) {
                    LOG.error("bad bearing '" + bearing.getText() + "'", ex);
                    JOptionPane.showMessageDialog(frame, "Bearing must be a number", "Input error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        side.add(bl);
        side.add(bearing);
        side.add(Box.createVerticalStrut(20));
        recompute = new JButton("Recompute");
        recompute.setMnemonic(KeyEvent.VK_R);
        recompute.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { recompute(); }
        });
        side.add(recompute);
        side.add(Box.createVerticalGlue());
        p.add(side, BorderLayout.EAST);
        return p;
    }

    JPanel narrowbandPanel() {
        JPanel p = new JPanel(new BorderLayout());
        contactTable.setName("contacts");
        contactTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && contactTable.getSelectedRow() >= 0) {
                    showContact(contactTable.getSelectedRow());
                } else if (contactTable.getSelectedRow() >= 0) {
                    status.setText("Selected " + contacts.getValueAt(contactTable.getSelectedRow(), 0));
                }
            }
        });
        p.add(new JScrollPane(contactTable), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton details = new JButton("Details...");
        details.setMnemonic(KeyEvent.VK_D);
        details.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int row = contactTable.getSelectedRow();
                if (row < 0) JOptionPane.showMessageDialog(frame, "Select a contact first.", "Details", JOptionPane.WARNING_MESSAGE);
                else showContact(row);
            }
        });
        JButton drop = new JButton("Drop Contact");
        drop.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int row = contactTable.getSelectedRow();
                if (row >= 0 && JOptionPane.showConfirmDialog(frame, "Drop " + contacts.getValueAt(row, 0) + "?", "Drop contact",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    contacts.removeRow(row);
                    LOG.info("contact dropped, " + contacts.getRowCount() + " left");
                }
            }
        });
        buttons.add(details);
        buttons.add(drop);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    JPanel historyPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(new JList(historyModel)), BorderLayout.CENTER);
        clearHistory = new JButton("Clear History");
        clearHistory.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { historyModel.clear(); }
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT));
        south.add(clearHistory);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    JPanel statusBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.setBorder(BorderFactory.createEtchedBorder());
        p.add(status);
        return p;
    }

    void addContact() {
        String id = "S" + (100 + contacts.getRowCount());
        contacts.addRow(new Object[] {id, RND.nextInt(360), 1000 + RND.nextInt(20000), RND.nextBoolean() ? "Merchant" : "Unknown"});
        historyModel.addElement(id + " acquired");
    }

    void showContact(int row) {
        final JDialog d = new JDialog(frame, "Contact Details", true);
        JTextArea text = new JTextArea("Contact " + contacts.getValueAt(row, 0) + "\nBearing " + contacts.getValueAt(row, 1)
                + "\nRange " + contacts.getValueAt(row, 2) + "\nClass " + contacts.getValueAt(row, 3));
        text.setEditable(false);
        d.getContentPane().add(text, BorderLayout.CENTER);
        JButton close = new JButton("Close");
        close.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { d.dispose(); }
        });
        JPanel south = new JPanel();
        south.add(close);
        d.getContentPane().add(south, BorderLayout.SOUTH);
        d.setSize(300, 200);
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
    }

    void recompute() {
        status.setText("Recomputing bearings...");
        LOG.info("recompute started");
        // Blocks the event thread on purpose: the UI freezes for 3 s.
        try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
        for (int i = 0; i < contacts.getRowCount(); i++) contacts.setValueAt(RND.nextInt(360), i, 1);
        status.setText("Bearings recomputed");
        System.out.println("stdout: recompute done");
    }

    void exit() {
        if (JOptionPane.showConfirmDialog(frame, "Exit Sonar Console?", "Exit", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            LOG.info("exiting");
            System.exit(0);
        }
    }

    /** Test harness only: screen coordinates of the controls the scripted click list needs. */
    void dumpBounds() {
        try {
            PrintWriter w = new PrintWriter(new FileWriter("/tmp/sonar-console-bounds.sh.tmp"));
            put(w, "zoom_in", zoomIn); put(w, "zoom_out", zoomOut); put(w, "mark", mark);
            put(w, "auto_track", autoTrack); put(w, "band", band); put(w, "gain", gain); put(w, "averaging", averaging);
            put(w, "bearing", bearing); put(w, "recompute", recompute); put(w, "waterfall", waterfall);
            put(w, "contacts", contactTable); put(w, "clear_history", clearHistory);
            if (calibration != null) { put(w, "cal_start", calibration.start); put(w, "cal_stop", calibration.stop); put(w, "cal_loopback", calibration.loopback); }
            JMenuBar bar = frame.getJMenuBar();
            for (int i = 0; i < bar.getMenuCount(); i++) put(w, "menu_" + bar.getMenu(i).getText().toLowerCase(), bar.getMenu(i));
            for (int i = 0; i < tabs.getTabCount(); i++) {
                java.awt.Rectangle r = tabs.getBoundsAt(i);
                Point o = tabs.getLocationOnScreen();
                w.println("tab_" + tabs.getTitleAt(i).toLowerCase() + "=\"" + (o.x + r.x + r.width / 2) + " " + (o.y + r.y + r.height / 2) + "\"");
            }
            w.close();
            new java.io.File("/tmp/sonar-console-bounds.sh.tmp").renameTo(new java.io.File("/tmp/sonar-console-bounds.sh"));
        } catch (Exception e) {
            // not showing; try again next tick
        }
    }

    static void put(PrintWriter w, String name, java.awt.Component c) {
        if (!c.isShowing()) return;
        Point p = c.getLocationOnScreen();
        w.println(name + "=\"" + (p.x + c.getWidth() / 2) + " " + (p.y + c.getHeight() / 2) + "\"");
        w.println(name + "_tl=\"" + p.x + " " + p.y + "\"");
        w.println(name + "_wh=\"" + c.getWidth() + " " + c.getHeight() + "\"");
    }

    /** Custom-painted component with its own mouse handling, like most legacy display panels. */
    class Waterfall extends JComponent {
        int zoom = 1, cursor = 180;
        final List<Point> marks = new ArrayList<Point>();

        Waterfall() {
            setBackground(Color.BLACK);
            setOpaque(true);
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    marks.add(e.getPoint());
                    cursor = e.getX() * 360 / Math.max(1, getWidth());
                    status.setText("Cursor at " + cursor);
                    repaint();
                }
            });
        }

        void zoom(int d) { zoom = Math.max(1, Math.min(8, zoom + d)); repaint(); }
        void markCentre() { marks.add(new Point(getWidth() / 2, getHeight() / 2)); repaint(); }

        protected void paintComponent(Graphics g) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            for (int y = 0; y < getHeight(); y += 4) {
                for (int x = 0; x < getWidth(); x += 4 * zoom) {
                    int v = RND.nextInt(80);
                    g.setColor(new Color(0, v + 40, v + 60));
                    g.fillRect(x, y, 4 * zoom, 4);
                }
            }
            g.setColor(Color.YELLOW);
            int cx = cursor * getWidth() / 360;
            g.drawLine(cx, 0, cx, getHeight());
            g.setColor(Color.RED);
            for (Point m : marks) g.drawOval(m.x - 5, m.y - 5, 10, 10);
            g.setColor(Color.WHITE);
            g.drawString("zoom x" + zoom + "  cursor " + cursor, 10, 15);
        }
    }

    /** Old-style AWT window (not Swing). */
    class Calibration extends java.awt.Frame {
        final java.awt.Label state = new java.awt.Label("Idle");
        final java.awt.Button start = new java.awt.Button("Start");
        final java.awt.Button stop = new java.awt.Button("Stop");
        final java.awt.Checkbox loopback = new java.awt.Checkbox("Loopback");

        Calibration() {
            super("Calibration");
            setLayout(new FlowLayout());
            start.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { state.setText("Calibrating" + (loopback.getState() ? " (loopback)" : "")); }
            });
            stop.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { state.setText("Idle"); }
            });
            add(start); add(stop); add(loopback); add(state);
            addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) { setVisible(false); }
            });
            setSize(320, 120);
            setLocation(1024 - 320, 0);
        }
    }
}
