package de.synchronizetool.ui;

import de.synchronizetool.core.PackageDownloader;
import de.synchronizetool.core.PackageServer;
import de.synchronizetool.core.PackageService;
import de.synchronizetool.core.RecentConfigs;
import de.synchronizetool.model.SyncConfig;
import de.synchronizetool.model.SyncEntry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Hauptfenster mit Reitern für Quelle, Ziel und Netzwerk. */
public final class MainFrame extends JFrame {

    private final SyncConfig sourceConfig = new SyncConfig();
    private SyncConfig targetConfig;

    private final DefaultListModel<SyncEntry> sourceModel = new DefaultListModel<>();
    private final DefaultListModel<SyncEntry> targetModel = new DefaultListModel<>();
    private final JList<SyncEntry> sourceList = new JList<>(sourceModel);
    private final JList<SyncEntry> targetList = new JList<>(targetModel);
    private final JTextField sourcePackageField = new JTextField(defaultPackageDir().toString());
    private final JTextField targetPackageField = new JTextField(defaultPackageDir().toString());
    private final JTextArea logArea = new JTextArea(10, 90);
    private final RecentConfigs recentConfigs = new RecentConfigs(RecentConfigs.defaultFile(), this::log);
    private final JComboBox<RecentConfigs.Entry> recentBox = new JComboBox<>();

    private final JTextField serverPortField = new JTextField("8765");
    private final JTextField shareUrlField = new JTextField();
    private final JTextField downloadUrlField = new JTextField();
    private final JTextField downloadDirField = new JTextField(defaultDownloadDir().toString());
    private final JLabel serverStatus = new JLabel("Nicht aktiv");

    private final PackageService service = new PackageService(this::log);
    private final PackageDownloader downloader = new PackageDownloader(this::log);
    private final PackageServer packageServer = new PackageServer(this::log);

    public MainFrame() {
        super("SynchronizeTool 0.3.0");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Quelle: auswählen & Paket erstellen", buildSourcePanel());
        tabs.addTab("Ziel: Paket einspielen", buildTargetPanel());
        tabs.addTab("Netzwerk", buildNetworkPanel());
        add(tabs, BorderLayout.CENTER);
        add(buildLogPanel(), BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                packageServer.stop();
            }
        });

        setSize(1040, 780);
        setLocationRelativeTo(null);
        refreshRecentConfigs();
        log("Bereit. Quelle: Daten auswählen und Paket erstellen. Ziel: Vorschau oder Synchronisieren."
                + " Netzwerk: Paket direkt im LAN übertragen.");
        if (!recentConfigs.entries().isEmpty()) {
            log(recentConfigs.entries().size()
                    + " gespeicherte Konfiguration(en) gefunden – im Quelle-Reiter auswählbar.");
        }
    }

    // ---------------------------------------------------------------- Quelle

    private JPanel buildSourcePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new BorderLayout(6, 4));
        top.add(new JLabel("Paketverzeichnis (diesen Ordner später auf Rechner 2 kopieren):"), BorderLayout.NORTH);
        top.add(sourcePackageField, BorderLayout.CENTER);
        JButton choosePackage = new JButton("...");
        choosePackage.addActionListener(e -> chooseDirectory(sourcePackageField));
        top.add(choosePackage, BorderLayout.EAST);

        JPanel recentRow = new JPanel(new BorderLayout(6, 4));
        recentRow.add(new JLabel("Gespeicherte Konfigurationen:"), BorderLayout.WEST);
        recentBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof RecentConfigs.Entry entry) {
                    String suffix = Files.isRegularFile(entry.configFile()) ? "" : "   (nicht gefunden)";
                    setText(entry.configFile() + suffix);
                }
                return this;
            }
        });
        recentRow.add(recentBox, BorderLayout.CENTER);

        JPanel recentButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton loadRecent = new JButton("Laden");
        loadRecent.addActionListener(e -> loadSelectedRecent());
        JButton openConfig = new JButton("Öffnen...");
        openConfig.addActionListener(e -> openPackageOrConfig());
        JButton forget = new JButton("Aus Liste entfernen");
        forget.addActionListener(e -> forgetSelectedRecent());
        recentButtons.add(loadRecent);
        recentButtons.add(openConfig);
        recentButtons.add(forget);
        recentRow.add(recentButtons, BorderLayout.EAST);

        JPanel north = new JPanel(new BorderLayout(0, 4));
        north.add(top, BorderLayout.NORTH);
        north.add(recentRow, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.add(new JLabel("Ausgewählte Dateien und Verzeichnisse:"), BorderLayout.NORTH);

        sourceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        center.add(new JScrollPane(sourceList), BorderLayout.CENTER);

        JPanel listButtons = new JPanel(new GridLayout(0, 1, 4, 4));
        JButton addFile = new JButton("Datei hinzufügen");
        addFile.addActionListener(e -> addFiles());
        JButton addDirectory = new JButton("Verzeichnis hinzufügen");
        addDirectory.addActionListener(e -> addDirectory());
        JButton remove = new JButton("Entfernen");
        remove.addActionListener(e -> removeSelected());
        listButtons.add(addFile);
        listButtons.add(addDirectory);
        listButtons.add(remove);
        center.add(listButtons, BorderLayout.EAST);

        panel.add(center, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton newPackage = new JButton("Neues Paket...");
        newPackage.addActionListener(e -> createNewPackage());
        JButton saveConfig = new JButton("Konfiguration speichern...");
        saveConfig.addActionListener(e -> saveConfig());
        JButton excludes = new JButton("Ausschlüsse...");
        excludes.addActionListener(e -> editExcludes());
        JButton createPackage = new JButton("Paket erstellen (Export)");
        createPackage.addActionListener(e -> createPackage());
        actions.add(newPackage);
        actions.add(saveConfig);
        actions.add(excludes);
        actions.add(createPackage);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void createNewPackage() {
        JTextField nameField = new JTextField(
                "paket-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        JTextField parentField = new JTextField(defaultPackagesRoot().toString());
        JCheckBox keepSelection = new JCheckBox("Aktuelle Auswahl übernehmen", !sourceConfig.isEmpty());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        form.add(new JLabel("Paketname:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        form.add(nameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        form.add(new JLabel("Speicherort:"), gbc);
        JPanel parentRow = new JPanel(new BorderLayout(4, 0));
        parentRow.add(parentField, BorderLayout.CENTER);
        JButton chooseParent = new JButton("...");
        chooseParent.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(parentField.getText());
            chooser.setDialogTitle("Speicherort für das neue Paket");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                parentField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        parentRow.add(chooseParent, BorderLayout.EAST);
        gbc.gridx = 1;
        gbc.weightx = 1;
        form.add(parentRow, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        form.add(keepSelection, gbc);

        gbc.gridy = 3;
        form.add(new JLabel("<html>Das neue Paket wird unter <i>Speicherort/Paketname</i> angelegt.<br>"
                + "Ein bereits vorhandenes Paket bleibt unverändert.</html>"), gbc);

        while (true) {
            int result = JOptionPane.showConfirmDialog(this, form, "Neues Paket anlegen",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return;
            }

            String name = nameField.getText().trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..")
                    || name.matches(".*[\\\\/:*?\"<>|].*")) {
                JOptionPane.showMessageDialog(this,
                        "Bitte einen gültigen Paketnamen ohne Pfadtrenner oder Sonderzeichen angeben.",
                        "Ungültiger Name", JOptionPane.ERROR_MESSAGE);
                continue;
            }

            if (parentField.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Bitte einen Speicherort angeben.",
                        "Fehlender Speicherort", JOptionPane.ERROR_MESSAGE);
                continue;
            }
            Path parent;
            try {
                parent = Paths.get(parentField.getText().trim()).toAbsolutePath().normalize();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Ungültiger Speicherort.",
                        "Fehler", JOptionPane.ERROR_MESSAGE);
                continue;
            }
            if (Files.exists(parent) && !Files.isDirectory(parent)) {
                JOptionPane.showMessageDialog(this, "Der Speicherort ist kein Verzeichnis.",
                        "Fehler", JOptionPane.ERROR_MESSAGE);
                continue;
            }
            Path newPackage = parent.resolve(name).normalize();
            if (!parent.equals(newPackage.getParent())) {
                JOptionPane.showMessageDialog(this, "Ungültiger Paketname.",
                        "Fehler", JOptionPane.ERROR_MESSAGE);
                continue;
            }
            if (Files.exists(newPackage)) {
                JOptionPane.showMessageDialog(this,
                        "An diesem Speicherort existiert bereits \"" + name + "\".\n"
                                + "Bitte einen anderen Namen wählen.",
                        "Paket existiert bereits", JOptionPane.ERROR_MESSAGE);
                continue;
            }

            boolean keep = keepSelection.isSelected();
            SyncConfig config = keep ? sourceConfig : new SyncConfig();
            if (!keep) {
                config.setExcludes(sourceConfig.excludes());
            }
            runTask(() -> {
                Files.createDirectories(newPackage);
                service.createPackage(config, newPackage);
                Path configFile = newPackage.resolve(PackageService.CONFIG_FILE);
                recentConfigs.remember(configFile);
                SwingUtilities.invokeLater(() -> {
                    sourcePackageField.setText(newPackage.toString());
                    if (!keep) {
                        sourceConfig.clearEntries();
                        refreshSourceList();
                    }
                    refreshRecentConfigs(configFile);
                });
                log("Neues Paket angelegt: " + newPackage);
            });
            return;
        }
    }

    private void addFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Dateien auswählen");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        for (File file : chooser.getSelectedFiles()) {
            if (!sourceConfig.add(SyncEntry.file(file.toPath()))) {
                log("Nicht übernommen (doppelt oder bereits in einem Verzeichnis enthalten): " + file);
            }
        }
        refreshSourceList();
    }

    private void addDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Verzeichnis auswählen");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File directory = chooser.getSelectedFile();
        if (!sourceConfig.add(SyncEntry.directory(directory.toPath()))) {
            log("Nicht übernommen (doppelt oder bereits enthalten): " + directory);
        }
        refreshSourceList();
    }

    private void removeSelected() {
        int index = sourceList.getSelectedIndex();
        if (index >= 0) {
            sourceConfig.remove(index);
            refreshSourceList();
        }
    }

    private void editExcludes() {
        JTextArea area = new JTextArea(String.join(System.lineSeparator(), sourceConfig.excludes()), 10, 48);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel wrapper = new JPanel(new BorderLayout(0, 6));
        wrapper.add(new JLabel("Ein Muster pro Zeile, z. B. *.log, node_modules, **/cache/**. "
                + "Ausgeschlossene Dateien werden nicht gepackt und im Ziel nicht angetastet."), BorderLayout.NORTH);
        wrapper.add(new JScrollPane(area), BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(this, wrapper, "Ausschlüsse",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            List<String> patterns = area.getText().lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
            sourceConfig.setExcludes(patterns);
            log("Ausschlüsse gesetzt (" + patterns.size() + "): " + patterns);
        }
    }

    private void saveConfig() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Konfiguration speichern");
        chooser.setSelectedFile(new File("synctool-config.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        try {
            sourceConfig.save(file);
            recentConfigs.remember(file);
            refreshRecentConfigs(file);
            log("Konfiguration gespeichert: " + file);
        } catch (Exception e) {
            log("FEHLER beim Speichern: " + e.getMessage());
            JOptionPane.showMessageDialog(this, e.getMessage(), "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void createPackage() {
        Path packageDir = packagePath(sourcePackageField.getText());
        if (packageDir == null) {
            return;
        }
        runTask(() -> {
            service.createPackage(sourceConfig, packageDir);
            Path configFile = packageDir.resolve(PackageService.CONFIG_FILE);
            recentConfigs.remember(configFile);
            SwingUtilities.invokeLater(() -> refreshRecentConfigs(configFile));
        });
    }

    private void refreshRecentConfigs() {
        refreshRecentConfigs(null);
    }

    private void refreshRecentConfigs(Path select) {
        recentBox.removeAllItems();
        for (RecentConfigs.Entry entry : recentConfigs.entries()) {
            recentBox.addItem(entry);
        }
        if (select != null) {
            Path normalized = select.toAbsolutePath().normalize();
            for (int i = 0; i < recentBox.getItemCount(); i++) {
                if (recentBox.getItemAt(i).configFile().equals(normalized)) {
                    recentBox.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private void loadSelectedRecent() {
        RecentConfigs.Entry entry = (RecentConfigs.Entry) recentBox.getSelectedItem();
        if (entry == null) {
            JOptionPane.showMessageDialog(this,
                    "Es ist noch keine gespeicherte Konfiguration vorhanden.",
                    "Hinweis", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        loadConfigFile(entry.configFile());
    }

    private void openPackageOrConfig() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Paketordner oder Konfigurationsdatei öffnen");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        List<RecentConfigs.Entry> known = recentConfigs.entries();
        if (!known.isEmpty()) {
            Path parent = known.get(0).configFile().getParent();
            if (parent != null && Files.isDirectory(parent)) {
                chooser.setCurrentDirectory(parent.toFile());
            }
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        Path configFile = Files.isDirectory(selected)
                ? selected.resolve(PackageService.CONFIG_FILE)
                : selected;
        loadConfigFile(configFile);
    }

    private void forgetSelectedRecent() {
        RecentConfigs.Entry entry = (RecentConfigs.Entry) recentBox.getSelectedItem();
        if (entry == null) {
            return;
        }
        recentConfigs.remove(entry.configFile());
        refreshRecentConfigs();
        log("Aus der Liste entfernt (Dateien bleiben erhalten): " + entry.configFile());
    }

    private void loadConfigFile(Path configFile) {
        Path file = configFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            JOptionPane.showMessageDialog(this,
                    "Konfigurationsdatei nicht gefunden:\n" + file,
                    "Nicht gefunden", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!sourceConfig.isEmpty() || !sourceConfig.excludes().isEmpty()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Die aktuelle Auswahl wird durch die geladene Konfiguration ersetzt.\n\nFortfahren?",
                    "Konfiguration laden", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            SyncConfig loaded = SyncConfig.load(file);
            sourceConfig.clearEntries();
            for (SyncEntry entry : loaded.entries()) {
                sourceConfig.add(entry);
            }
            sourceConfig.setExcludes(loaded.excludes());
            refreshSourceList();

            boolean isPackageConfig = file.getFileName().toString().equals(PackageService.CONFIG_FILE);
            Path packageDir;
            if (isPackageConfig && file.getParent() != null) {
                packageDir = file.getParent();
            } else {
                packageDir = defaultPackageDir();
                log("Hinweis: Die Datei heißt nicht \"" + PackageService.CONFIG_FILE
                        + "\"; als Paketverzeichnis wird " + packageDir + " verwendet.");
            }
            sourcePackageField.setText(packageDir.toString());

            recentConfigs.remember(file);
            refreshRecentConfigs(file);
            log("Konfiguration geladen: " + file + " (" + loaded.entries().size()
                    + " Einträge, " + loaded.excludes().size() + " Ausschlüsse)");
            log("Paketverzeichnis: " + packageDir);
        } catch (Exception e) {
            log("FEHLER beim Laden: " + e.getMessage());
            JOptionPane.showMessageDialog(this, e.getMessage(),
                    "Fehler beim Laden", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ Ziel

    private JPanel buildTargetPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new BorderLayout(6, 4));
        top.add(new JLabel("Paketverzeichnis (von Rechner 1 hierher kopiert):"), BorderLayout.NORTH);
        top.add(targetPackageField, BorderLayout.CENTER);
        JButton choosePackage = new JButton("...");
        choosePackage.addActionListener(e -> chooseDirectory(targetPackageField));
        top.add(choosePackage, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.add(new JLabel("Inhalt der config.txt:"), BorderLayout.NORTH);
        targetList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        center.add(new JScrollPane(targetList), BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton load = new JButton("Konfiguration anzeigen");
        load.addActionListener(e -> previewTargetConfig());
        JButton dryRun = new JButton("Vorschau (Dry Run)");
        dryRun.addActionListener(e -> dryRunTarget());
        JButton sync = new JButton("Synchronisieren (Sicherung + Kopieren)");
        sync.addActionListener(e -> synchronize());
        JButton rollback = new JButton("Letzte Synchronisation rückgängig");
        rollback.addActionListener(e -> rollbackLastSync());
        actions.add(load);
        actions.add(dryRun);
        actions.add(sync);
        actions.add(rollback);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void previewTargetConfig() {
        Path packageDir = packagePath(targetPackageField.getText());
        if (packageDir == null) {
            return;
        }
        runTask(() -> {
            SyncConfig loaded = service.loadConfig(packageDir);
            SwingUtilities.invokeLater(() -> {
                targetConfig = loaded;
                refreshTargetList();
            });
        });
    }

    private void dryRunTarget() {
        Path packageDir = packagePath(targetPackageField.getText());
        if (packageDir == null) {
            return;
        }
        runTask(() -> {
            SyncConfig config = service.loadConfig(packageDir);
            SwingUtilities.invokeLater(() -> {
                targetConfig = config;
                refreshTargetList();
            });
            service.dryRun(config, packageDir);
        });
    }

    private void synchronize() {
        Path packageDir = packagePath(targetPackageField.getText());
        if (packageDir == null || !confirmSync()) {
            return;
        }
        runTask(() -> {
            SyncConfig config = service.loadConfig(packageDir);
            SwingUtilities.invokeLater(() -> {
                targetConfig = config;
                refreshTargetList();
            });
            service.apply(config, packageDir);
        });
    }

    private void rollbackLastSync() {
        if (!service.hasRollback()) {
            JOptionPane.showMessageDialog(this,
                    "Es ist keine Rollback-Datei vorhanden.\n"
                            + "Es wurde auf diesem Rechner noch nichts mit dem Tool eingespielt.",
                    "Kein Rollback möglich", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Die letzte Synchronisation wird rückgängig gemacht:\n"
                        + "Sicherungen werden zurückgespielt, die aktuellen Dateien werden\n"
                        + "mit dem Zusatz \"_rollback_<Timestamp>\" zur Seite gelegt.\n\nFortfahren?",
                "Rollback", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        runTask(service::rollbackLastSync);
    }

    private boolean confirmSync() {
        return JOptionPane.showConfirmDialog(
                this,
                "Vorhandene Dateien und Verzeichnisse werden mit einem Timestamp gesichert\n"
                        + "und anschließend durch die Version aus dem Paket ersetzt.\n\nFortfahren?",
                "Synchronisieren",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    // -------------------------------------------------------------- Netzwerk

    private JPanel buildNetworkPanel() {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
        outer.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel serverSection = new JPanel();
        serverSection.setLayout(new BoxLayout(serverSection, BoxLayout.Y_AXIS));
        serverSection.setBorder(BorderFactory.createTitledBorder(
                "Rechner 1: Paket im lokalen Netzwerk freigeben"));

        JPanel serverRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        serverRow.add(new JLabel("Port:"));
        serverPortField.setColumns(6);
        serverRow.add(serverPortField);
        JButton share = new JButton("Paket erstellen & freigeben");
        share.addActionListener(e -> startShare());
        JButton stop = new JButton("Freigabe beenden");
        stop.addActionListener(e -> stopShare());
        serverRow.add(share);
        serverRow.add(stop);
        serverRow.add(serverStatus);
        serverSection.add(serverRow);

        JPanel urlRow = new JPanel(new BorderLayout(6, 4));
        urlRow.add(new JLabel("URL für Rechner 2:"), BorderLayout.WEST);
        shareUrlField.setEditable(false);
        urlRow.add(shareUrlField, BorderLayout.CENTER);
        JButton copy = new JButton("Kopieren");
        copy.addActionListener(e -> copyShareUrl());
        urlRow.add(copy, BorderLayout.EAST);
        serverSection.add(urlRow);
        outer.add(serverSection);

        outer.add(Box.createVerticalStrut(12));

        JPanel clientSection = new JPanel();
        clientSection.setLayout(new BoxLayout(clientSection, BoxLayout.Y_AXIS));
        clientSection.setBorder(BorderFactory.createTitledBorder(
                "Rechner 2: Paket direkt laden und einspielen"));

        JPanel downloadRow = new JPanel(new BorderLayout(6, 4));
        downloadRow.add(new JLabel("URL:"), BorderLayout.WEST);
        downloadRow.add(downloadUrlField, BorderLayout.CENTER);
        JButton paste = new JButton("Einfügen");
        paste.addActionListener(e -> pasteUrl());
        downloadRow.add(paste, BorderLayout.EAST);
        clientSection.add(downloadRow);

        JPanel destinationRow = new JPanel(new BorderLayout(6, 4));
        destinationRow.add(new JLabel("Zielordner:"), BorderLayout.WEST);
        destinationRow.add(downloadDirField, BorderLayout.CENTER);
        JButton chooseDownloadDir = new JButton("...");
        chooseDownloadDir.addActionListener(e -> chooseDirectory(downloadDirField));
        destinationRow.add(chooseDownloadDir, BorderLayout.EAST);
        clientSection.add(destinationRow);

        JPanel clientActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton downloadOnly = new JButton("Nur herunterladen");
        downloadOnly.addActionListener(e -> downloadPackage(DownloadMode.DOWNLOAD));
        JButton downloadPreview = new JButton("Herunterladen & Vorschau");
        downloadPreview.addActionListener(e -> downloadPackage(DownloadMode.PREVIEW));
        JButton downloadSync = new JButton("Herunterladen & Synchronisieren");
        downloadSync.addActionListener(e -> downloadPackage(DownloadMode.APPLY));
        clientActions.add(downloadOnly);
        clientActions.add(downloadPreview);
        clientActions.add(downloadSync);
        clientSection.add(clientActions);
        outer.add(clientSection);

        outer.add(Box.createVerticalGlue());
        return outer;
    }

    private void startShare() {
        Path packageDir = packagePath(sourcePackageField.getText());
        if (packageDir == null) {
            return;
        }
        int port = parsePort(serverPortField.getText());
        if (port < 0) {
            return;
        }
        runTask(() -> {
            service.createPackage(sourceConfig, packageDir);
            Path configFile = packageDir.resolve(PackageService.CONFIG_FILE);
            recentConfigs.remember(configFile);
            String url = packageServer.start(packageDir, port);
            SwingUtilities.invokeLater(() -> {
                shareUrlField.setText(url);
                downloadUrlField.setText(url);
                serverStatus.setText("aktiv auf Port " + packageServer.port());
                refreshRecentConfigs(configFile);
            });
        });
    }

    private void stopShare() {
        packageServer.stop();
        serverStatus.setText("Nicht aktiv");
    }

    private void copyShareUrl() {
        String url = shareUrlField.getText().trim();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Es ist noch keine URL vorhanden.",
                    "Hinweis", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
        log("URL in die Zwischenablage kopiert.");
    }

    private void pasteUrl() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
            if (data != null) {
                downloadUrlField.setText(data.toString().trim());
            }
        } catch (Exception e) {
            log("Zwischenablage konnte nicht gelesen werden: " + e.getMessage());
        }
    }

    private void downloadPackage(DownloadMode mode) {
        String url = downloadUrlField.getText().trim();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Bitte zuerst die URL von Rechner 1 einfügen.",
                    "Hinweis", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path destination;
        try {
            destination = Paths.get(downloadDirField.getText().trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Ungültiger Zielordner.",
                    "Fehler", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (mode == DownloadMode.APPLY && !confirmSync()) {
            return;
        }
        runTask(() -> {
            Path packageDir = downloader.download(url, destination);
            SyncConfig config = service.loadConfig(packageDir);
            SwingUtilities.invokeLater(() -> {
                targetConfig = config;
                refreshTargetList();
                targetPackageField.setText(packageDir.toString());
            });
            if (mode == DownloadMode.APPLY) {
                service.apply(config, packageDir);
            } else if (mode == DownloadMode.PREVIEW) {
                service.dryRun(config, packageDir);
            }
        });
    }

    private enum DownloadMode {
        DOWNLOAD, PREVIEW, APPLY
    }

    private int parsePort(String text) {
        try {
            int port = Integer.parseInt(text.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Bitte einen Port zwischen 1 und 65535 angeben.",
                    "Ungültiger Port", JOptionPane.ERROR_MESSAGE);
            return -1;
        }
    }

    // --------------------------------------------------------------- Gemeinsam

    private JPanel buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Protokoll"));
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private void refreshSourceList() {
        sourceModel.clear();
        for (SyncEntry entry : sourceConfig.entries()) {
            sourceModel.addElement(entry);
        }
    }

    private void refreshTargetList() {
        targetModel.clear();
        if (targetConfig != null) {
            for (SyncEntry entry : targetConfig.entries()) {
                targetModel.addElement(entry);
            }
        }
    }

    private void chooseDirectory(JTextField field) {
        JFileChooser chooser = new JFileChooser(field.getText());
        chooser.setDialogTitle("Verzeichnis auswählen");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private Path packagePath(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Bitte zuerst ein Paketverzeichnis auswählen.",
                    "Hinweis", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        try {
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Ungültiger Pfad: " + value,
                    "Fehler", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private void runTask(ThrowingRunnable task) {
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                try {
                    task.run();
                } catch (Exception e) {
                    publish("FEHLER: " + e.getMessage());
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(MainFrame.this::log);
            }
        }.execute();
    }

    private void log(String message) {
        Runnable append = () -> {
            logArea.append(message + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            append.run();
        } else {
            SwingUtilities.invokeLater(append);
        }
    }

    private static Path defaultPackageDir() {
        return Paths.get(System.getProperty("user.home"), "SyncTool-Paket");
    }

    private static Path defaultPackagesRoot() {
        return Paths.get(System.getProperty("user.home"), "SyncTool-Pakete");
    }

    private static Path defaultDownloadDir() {
        return Paths.get(System.getProperty("user.home"), "SyncTool-Download");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
