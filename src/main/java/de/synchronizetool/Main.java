package de.synchronizetool;

import de.synchronizetool.ui.MainFrame;

import javax.swing.SwingUtilities;

/** Einstiegspunkt der Anwendung. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}
