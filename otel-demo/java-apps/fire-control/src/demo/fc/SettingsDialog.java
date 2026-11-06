package demo.fc;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Legacy Swing settings dialog opened from the JavaFX app, so one JVM runs both toolkits. */
class SettingsDialog {
    static void show() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                final JDialog d = new JDialog((java.awt.Frame) null, "Settings", false);
                JPanel body = new JPanel(new FlowLayout(FlowLayout.LEFT));
                body.add(new JLabel("Legacy settings"));
                JCheckBox verbose = new JCheckBox("Verbose logging");
                verbose.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) { FireControl.LOG.info("verbose logging toggled"); }
                });
                body.add(verbose);
                JButton ok = new JButton("OK");
                ok.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) { d.dispose(); }
                });
                JPanel south = new JPanel();
                south.add(ok);
                d.getContentPane().add(body, BorderLayout.CENTER);
                d.getContentPane().add(south, BorderLayout.SOUTH);
                d.setSize(300, 140);
                d.setLocation(1024 - 300, 200);
                d.setVisible(true);
            }
        });
    }
}
