package de.synchronizetool.ui;

import de.synchronizetool.core.LogFiles;
import de.synchronizetool.core.PackageCombiner;
import de.synchronizetool.core.PackageDownloader;
import de.synchronizetool.core.PackageServer;
import de.synchronizetool.core.PackageService;
import de.synchronizetool.core.RecentConfigs;
import de.synchronizetool.core.ToolSettings;
import de.synchronizetool.model.SyncConfig;
import de.synchronizetool.model.SyncEntry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
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
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
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
import java.util.ArrayList;
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
    private final ToolSettings settings = new ToolSettings(ToolSettings.defaultFile());
    private final JComboBox<RecentConfigs.Entry> recentBox = new JComboBox<>();
    /** Zuletzt geladene bzw. gespeicherte Konfiguration – Vorgabe für „Konfiguration speichern...“. */
    private Path currentConfigFile;

    private final JRadioButton modeChangedOnly = new JRadioButton(
            "Nur geänderte Dateien (schnell, Sicherung pro Datei)");
    private final JRadioButton modeReplaceAll = new JRadioButton(
            "Alles ersetzen (vollständig, Sicherung pro Eintrag)");
    private final ButtonGroup copyModeGroup = new ButtonGroup();

    private final JTextField serverPortField = new JTextField("8765");
    private final JTextField shareUrlField = new JTextField();
    private final JTextField downloadUrlField = new JTextField();
    private final JTextField downloadDirField = new JTextField(defaultDownloadDir().toString());
    private final JLabel serverStatus = new JLabel("Nicht aktiv");

    private final LogFiles logFiles = new LogFiles(LogFiles.defaultBaseDir());
    private final ThreadLocal<LogFiles.Session> activeLog = new ThreadLocal<>();
    private final JComboBox<LogFiles.Category> logCategoryBox = new JComboBox<>(LogFiles.Category.values());
    private final DefaultListModel<Path> logFileModel = new DefaultListModel<>();
    private final JList<Path> logFileList = new JList<>(logFileModel);
    private final JTextArea logFileArea = new JTextArea();
    private final JLabel logDirectoryLabel = new JLabel();

    private final PackageService service = new PackageService(this::log);
    private final PackageCombiner combiner = new PackageCombiner(this::log);
    private final PackageDownloader downloader = new PackageDownloader(this::log);
    private final PackageServer packageServer = new PackageServer(this::log);

    public MainFrame() {
        super("SynchronizeTool 0.5.0");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Quelle: auswählen & Paket erstellen", buildSourcePanel());
        tabs.addTab("Ziel: Paket einspielen", buildTargetPanel());
        tabs.addTab("Netzwerk", buildNetworkPanel());
        tabs.addTab("Protokolle", buildLogFilesPanel());
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
        refreshLogFiles(null);
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
        JButton combinePackages = new JButton("Pakete kombinieren...");
        combinePackages.addActionListener(e -> combinePackages());
        JButton saveConfig = new JButton("Konfiguration speichern...");
        saveConfig.addActionListener(e -> saveConfig());
        JButton excludes = new JButton("Ausschlüsse...");
        excludes.addActionListener(e -> editExcludes());
        JButton createPackage = new JButton("Paket erstellen (Export)");
        createPackage.addActionListener(e -> createPackage());
        actions.add(newPackage);
        actions.add(combinePackages);
        actions.add(saveConfig);
        actions.add(excludes);
        actions.add(createPackage);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void combinePackages() {
        DefaultListModel<Path> availableModel = new DefaultListModel<>();
        for (RecentConfigs.Entry entry : recentConfigs.entries()) {
            Path config = entry.configFile();
            if (isCombinablePackage(config) && !containsPath(availableModel, config)) {
                availableModel.addElement(config);
            }
        }
        DefaultListModel<Path> selectedModel = new DefaultListModel<>();

        JList<Path> availableList = new JList<>(availableModel);
        availableList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        availableList.setVisibleRowCount(9);
        availableList.setCellRenderer(packageListRenderer());
        JList<Path> selectedList = new JList<>(selectedModel);
        selectedList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        selectedList.setVisibleRowCount(9);
        selectedList.setCellRenderer(packageListRenderer());

        JButton add = new JButton("Hinzufügen →");
        add.addActionListener(e -> {
            for (Path path : availableList.getSelectedValuesList()) {
                if (!containsPath(selectedModel, path)) {
                    selectedModel.addElement(path);
                }
            }
            if (!selectedModel.isEmpty()) {
                selectedList.setSelectedIndex(selectedModel.size() - 1);
                selectedList.ensureIndexIsVisible(selectedModel.size() - 1);
            }
        });
        JButton addOther = new JButton("Weitere...");
        addOther.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Weiteres Paket auswählen");
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            Path config = Files.isDirectory(selected)
                    ? selected.resolve(PackageService.CONFIG_FILE)
                    : selected;
            if (!isCombinablePackage(config)) {
                JOptionPane.showMessageDialog(this,
                        "Das ausgewählte Verzeichnis enthält kein vollständiges Paket\n"
                                + "(config.txt und payload/ werden benötigt).",
                        "Kein vollständiges Paket", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!containsPath(availableModel, config)) {
                availableModel.addElement(config);
            }
            if (!containsPath(selectedModel, config)) {
                selectedModel.addElement(config);
            }
            selectedList.setSelectedValue(config, true);
            selectedList.ensureIndexIsVisible(selectedModel.indexOf(config));
        });
        JButton remove = new JButton("Entfernen");
        remove.addActionListener(e -> {
            int index = selectedList.getSelectedIndex();
            if (index >= 0) {
                selectedModel.remove(index);
                if (!selectedModel.isEmpty()) {
                    selectedList.setSelectedIndex(Math.min(index, selectedModel.size() - 1));
                }
            }
        });
        JButton up = new JButton("Nach oben");
        up.addActionListener(e -> moveSelected(selectedList, selectedModel, -1));
        JButton down = new JButton("Nach unten");
        down.addActionListener(e -> moveSelected(selectedList, selectedModel, 1));

        JPanel availablePanel = new JPanel(new BorderLayout(0, 4));
        availablePanel.add(new JLabel("Verfügbare Pakete:"), BorderLayout.NORTH);
        JScrollPane availableScroll = new JScrollPane(availableList);
        availableScroll.setPreferredSize(new Dimension(390, 220));
        availablePanel.add(availableScroll, BorderLayout.CENTER);

        JPanel selectedPanel = new JPanel(new BorderLayout(0, 4));
        selectedPanel.add(new JLabel("Ausgewählte Pakete (oben = Vorrang):"), BorderLayout.NORTH);
        JScrollPane selectedScroll = new JScrollPane(selectedList);
        selectedScroll.setPreferredSize(new Dimension(390, 220));
        selectedPanel.add(selectedScroll, BorderLayout.CENTER);

        JPanel middleButtons = new JPanel(new GridLayout(0, 1, 4, 4));
        middleButtons.add(add);
        middleButtons.add(addOther);
        middleButtons.add(remove);
        middleButtons.add(up);
        middleButtons.add(down);

        JPanel lists = new JPanel(new BorderLayout(8, 0));
        lists.add(availablePanel, BorderLayout.WEST);
        lists.add(middleButtons, BorderLayout.CENTER);
        lists.add(selectedPanel, BorderLayout.EAST);

        JTextField nameField = new JTextField(
                "gesamtpaket-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        JTextField parentField = new JTextField(defaultPackagesRoot().toString());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1;
        form.add(new JLabel("Mehrere vorhandene Pakete zu einem Gesamtpaket zusammenführen:"), gbc);

        gbc.gridy = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        form.add(lists, gbc);

        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(new JLabel("Paketname:"), gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        form.add(nameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        form.add(new JLabel("Speicherort:"), gbc);
        JPanel parentRow = new JPanel(new BorderLayout(4, 0));
        parentRow.add(parentField, BorderLayout.CENTER);
        JButton chooseParent = new JButton("...");
        chooseParent.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(parentField.getText());
            chooser.setDialogTitle("Speicherort für das Gesamtpaket");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                parentField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        parentRow.add(chooseParent, BorderLayout.EAST);
        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        form.add(parentRow, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 3;
        form.add(new JLabel("<html>Bei gleichen Zielpfaden gewinnt das <b>oberste</b> Paket. "
                + "Ausschlussmuster werden zusammengeführt.<br>Die Daten werden aus den "
                + "vorhandenen <i>payload</i>-Ordnern übernommen; die ursprünglichen Dateien "
                + "müssen nicht mehr existieren.</html>"), gbc);

        while (true) {
            int result = JOptionPane.showConfirmDialog(this, form, "Pakete kombinieren",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return;
            }
            if (selectedModel.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "Bitte mindestens ein Paket auswählen.",
                        "Keine Pakete ausgewählt", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            Path target = resolveNewPackagePath(nameField, parentField);
            if (target == null) {
                continue;
            }

            List<Path> selected = new ArrayList<>();
            for (int i = 0; i < selectedModel.size(); i++) {
                selected.add(selectedModel.get(i));
            }
            runLoggedTask(LogFiles.Category.CREATION, "pakete-kombinieren", () -> {
                log("Gesamtpaket: " + target);
                log("Enthaltene Pakete: " + selected);
                SyncConfig combined = combiner.combine(selected, target);
                Path configFile = target.resolve(PackageService.CONFIG_FILE);
                recentConfigs.remember(configFile);
                SwingUtilities.invokeLater(() -> {
                    sourcePackageField.setText(target.toString());
                    currentConfigFile = configFile;
                    sourceConfig.clearEntries();
                    for (SyncEntry entry : combined.entries()) {
                        sourceConfig.add(entry);
                    }
                    sourceConfig.setExcludes(combined.excludes());
                    refreshSourceList();
                    refreshRecentConfigs(configFile);
                    log("Gesamtpaket im Quelle-Reiter geladen.");
                });
            });
            return;
        }
    }

    private static DefaultListCellRenderer packageListRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Path path) {
                    setText(path.toString());
                }
                return this;
            }
        };
    }

    private static boolean containsPath(DefaultListModel<Path> model, Path path) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static void moveSelected(JList<Path> list, DefaultListModel<Path> model, int offset) {
        int index = list.getSelectedIndex();
        int target = index + offset;
        if (index < 0 || target < 0 || target >= model.size()) {
            return;
        }
        Path value = model.remove(index);
        model.add(target, value);
        list.setSelectedIndex(target);
        list.ensureIndexIsVisible(target);
    }

    private static boolean isCombinablePackage(Path configFile) {
        Path parent = configFile.getParent();
        return parent != null
                && Files.isRegularFile(configFile)
                && Files.isDirectory(parent.resolve(PackageService.PAYLOAD_DIR));
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

            Path newPackage = resolveNewPackagePath(nameField, parentField);
            if (newPackage == null) {
                continue;
            }

            boolean keep = keepSelection.isSelected();
            SyncConfig config = keep ? sourceConfig : new SyncConfig();
            if (!keep) {
                config.setExcludes(sourceConfig.excludes());
            }
            runLoggedTask(LogFiles.Category.CREATION, "neues-paket", () -> {
                log("Neues Paket: " + newPackage);
                log("Auswahl übernommen: " + (keep ? "ja" : "nein"));
                Files.createDirectories(newPackage);
                service.createPackage(config, newPackage);
                Path configFile = newPackage.resolve(PackageService.CONFIG_FILE);
                recentConfigs.remember(configFile);
                SwingUtilities.invokeLater(() -> {
                    sourcePackageField.setText(newPackage.toString());
                    currentConfigFile = configFile;
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

    /**
     * Validiert Paketname und Speicherort und liefert den neuen Paketpfad oder
     * {@code null}, wenn eine Meldung angezeigt wurde.
     */
    private Path resolveNewPackagePath(JTextField nameField, JTextField parentField) {
        String name = nameField.getText().trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.matches(".*[\\\\/:*?\"<>|].*")) {
            JOptionPane.showMessageDialog(this,
                    "Bitte einen gültigen Paketnamen ohne Pfadtrenner oder Sonderzeichen angeben.",
                    "Ungültiger Name", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        if (parentField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Bitte einen Speicherort angeben.",
                    "Fehlender Speicherort", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        Path parent;
        try {
            parent = Paths.get(parentField.getText().trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Ungültiger Speicherort.",
                    "Fehler", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        if (Files.exists(parent) && !Files.isDirectory(parent)) {
            JOptionPane.showMessageDialog(this, "Der Speicherort ist kein Verzeichnis.",
                    "Fehler", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        Path newPackage = parent.resolve(name).normalize();
        if (!parent.equals(newPackage.getParent())) {
            JOptionPane.showMessageDialog(this, "Ungültiger Paketname.",
                    "Fehler", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        if (Files.exists(newPackage)) {
            JOptionPane.showMessageDialog(this,
                    "An diesem Speicherort existiert bereits \"" + name + "\".\n"
                            + "Bitte einen anderen Namen wählen.",
                    "Paket existiert bereits", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        return newPackage;
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
        if (currentConfigFile != null) {
            Path parent = currentConfigFile.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                chooser.setCurrentDirectory(parent.toFile());
            }
            chooser.setSelectedFile(currentConfigFile.toFile());
        } else {
            chooser.setSelectedFile(new File("synctool-config.txt"));
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        try {
            sourceConfig.save(file);
            currentConfigFile = file;
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
        runLoggedTask(LogFiles.Category.CREATION, "paket-erstellen", () -> {
            log("Paketverzeichnis: " + packageDir);
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
            currentConfigFile = file;

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

        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        modePanel.setBorder(BorderFactory.createTitledBorder("Kopiermodus"));
        copyModeGroup.add(modeChangedOnly);
        copyModeGroup.add(modeReplaceAll);
        modeChangedOnly.setSelected(settings.copyMode() == PackageService.CopyMode.CHANGED_ONLY);
        modeReplaceAll.setSelected(settings.copyMode() == PackageService.CopyMode.REPLACE_ALL);
        modeChangedOnly.addActionListener(e -> saveCopyMode());
        modeReplaceAll.addActionListener(e -> saveCopyMode());
        modePanel.add(modeChangedOnly);
        modePanel.add(modeReplaceAll);

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.add(modePanel);
        south.add(actions);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private PackageService.CopyMode selectedCopyMode() {
        return modeReplaceAll.isSelected()
                ? PackageService.CopyMode.REPLACE_ALL
                : PackageService.CopyMode.CHANGED_ONLY;
    }

    private static String copyModeLabel(PackageService.CopyMode mode) {
        return mode == PackageService.CopyMode.CHANGED_ONLY
                ? "Nur geänderte Dateien"
                : "Alles ersetzen";
    }

    private void saveCopyMode() {
        settings.setCopyMode(selectedCopyMode());
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
        runLoggedTask(LogFiles.Category.APPLY, "vorschau", () -> {
            log("Paketverzeichnis: " + packageDir);
            log("Kopiermodus: " + copyModeLabel(selectedCopyMode()));
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
        PackageService.CopyMode mode = selectedCopyMode();
        runLoggedTask(LogFiles.Category.APPLY, "synchronisieren", () -> {
            log("Paketverzeichnis: " + packageDir);
            log("Kopiermodus: " + copyModeLabel(mode));
            SyncConfig config = service.loadConfig(packageDir);
            SwingUtilities.invokeLater(() -> {
                targetConfig = config;
                refreshTargetList();
            });
            service.apply(config, packageDir, mode);
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
        runLoggedTask(LogFiles.Category.APPLY, "rollback", service::rollbackLastSync);
    }

    private boolean confirmSync() {
        String message = selectedCopyMode() == PackageService.CopyMode.CHANGED_ONLY
                ? "Nur neue und geänderte Dateien werden kopiert. Unveränderte Dateien bleiben\n"
                        + "unangetastet. Nicht mehr vorhandene Dateien und leere Verzeichnisse werden\n"
                        + "mit einem Timestamp gesichert und aus dem Ziel entfernt.\n\nFortfahren?"
                : "Vorhandene Dateien und Verzeichnisse werden mit einem Timestamp gesichert\n"
                        + "und anschließend durch die Version aus dem Paket ersetzt.\n\nFortfahren?";
        return JOptionPane.showConfirmDialog(
                this,
                message,
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
        runLoggedTask(LogFiles.Category.CREATION, "paket-freigeben", () -> {
            log("Paketverzeichnis: " + packageDir);
            log("Port: " + port);
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
        PackageService.CopyMode copyMode = selectedCopyMode();
        ThrowingRunnable downloadTask = () -> {
            Path packageDir = downloader.download(url, destination);
            SyncConfig config = service.loadConfig(packageDir);
            Path configFile = packageDir.resolve(PackageService.CONFIG_FILE);
            recentConfigs.remember(configFile);
            SwingUtilities.invokeLater(() -> {
                targetConfig = config;
                refreshTargetList();
                targetPackageField.setText(packageDir.toString());
                refreshRecentConfigs(configFile);
            });
            if (mode == DownloadMode.APPLY) {
                log("Kopiermodus: " + copyModeLabel(copyMode));
                service.apply(config, packageDir, copyMode);
            } else if (mode == DownloadMode.PREVIEW) {
                service.dryRun(config, packageDir);
            }
        };
        if (mode == DownloadMode.DOWNLOAD) {
            runTask(downloadTask);
        } else {
            String label = mode == DownloadMode.APPLY ? "netzwerk-sync" : "netzwerk-vorschau";
            runLoggedTask(LogFiles.Category.APPLY, label, downloadTask);
        }
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

    private JPanel buildLogFilesPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel controls = new JPanel(new BorderLayout(6, 4));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(new JLabel("Bereich:"));
        row.add(logCategoryBox);
        JButton refresh = new JButton("Aktualisieren");
        refresh.addActionListener(e -> refreshLogFiles(null));
        JButton openFolder = new JButton("Logordner öffnen");
        openFolder.addActionListener(e -> openLogFolder());
        row.add(refresh);
        row.add(openFolder);
        controls.add(row, BorderLayout.NORTH);
        logDirectoryLabel.setFont(logDirectoryLabel.getFont().deriveFont(Font.PLAIN, 11f));
        controls.add(logDirectoryLabel, BorderLayout.SOUTH);
        panel.add(controls, BorderLayout.NORTH);

        logFileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        logFileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Path path) {
                    setText(path.getFileName().toString());
                    setToolTipText(path.toString());
                }
                return this;
            }
        });
        logFileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedLog();
            }
        });

        JScrollPane listScroll = new JScrollPane(logFileList);
        listScroll.setPreferredSize(new Dimension(340, 240));
        listScroll.setBorder(BorderFactory.createTitledBorder("Protokolldateien (neueste zuerst)"));

        logFileArea.setEditable(false);
        logFileArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane contentScroll = new JScrollPane(logFileArea);
        contentScroll.setBorder(BorderFactory.createTitledBorder("Protokollinhalt"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, contentScroll);
        split.setResizeWeight(0.25);
        split.setDividerLocation(340);
        panel.add(split, BorderLayout.CENTER);

        logCategoryBox.addActionListener(e -> refreshLogFiles(null));
        return panel;
    }

    private void refreshLogFiles(Path select) {
        LogFiles.Category category = (LogFiles.Category) logCategoryBox.getSelectedItem();
        if (category == null) {
            return;
        }
        Path previous = logFileList.getSelectedValue();
        logFileModel.clear();
        try {
            for (Path file : logFiles.list(category)) {
                logFileModel.addElement(file);
            }
        } catch (Exception e) {
            log("Protokolle konnten nicht gelesen werden: " + e.getMessage());
        }
        logDirectoryLabel.setText("Ablage: " + logFiles.directory(category));

        Path target = select != null ? select : previous;
        int index = indexOfLogFile(target);
        if (index < 0 && previous != null && !previous.equals(select)) {
            index = indexOfLogFile(previous);
        }
        if (index < 0 && !logFileModel.isEmpty()) {
            index = 0;
        }
        if (index >= 0) {
            logFileList.setSelectedIndex(index);
            logFileList.ensureIndexIsVisible(index);
        } else {
            showSelectedLog();
        }
    }

    private int indexOfLogFile(Path file) {
        if (file == null) {
            return -1;
        }
        for (int i = 0; i < logFileModel.size(); i++) {
            if (logFileModel.get(i).equals(file)) {
                return i;
            }
        }
        return -1;
    }

    private void showSelectedLog() {
        Path file = logFileList.getSelectedValue();
        if (file == null) {
            logFileArea.setText("Keine Protokolldatei ausgewählt.");
            return;
        }
        try {
            logFileArea.setText(Files.readString(file));
        } catch (Exception e) {
            logFileArea.setText("Protokoll konnte nicht gelesen werden:\n" + file + "\n\n" + e.getMessage());
        }
        logFileArea.setCaretPosition(0);
    }

    private void openLogFolder() {
        LogFiles.Category category = (LogFiles.Category) logCategoryBox.getSelectedItem();
        if (category == null) {
            return;
        }
        Path directory = logFiles.directory(category);
        try {
            Files.createDirectories(directory);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(directory.toFile());
            } else {
                JOptionPane.showMessageDialog(this, "Logordner:\n" + directory,
                        "Logordner", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            log("Logordner konnte nicht geöffnet werden: " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Logordner:\n" + directory,
                    "Logordner", JOptionPane.INFORMATION_MESSAGE);
        }
    }

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

    private void runLoggedTask(LogFiles.Category category, String label, ThrowingRunnable task) {
        LogFiles.Session session = startLog(category, label);
        if (session == null) {
            runTask(task);
        } else {
            runTask(session, task);
        }
    }

    private LogFiles.Session startLog(LogFiles.Category category, String label) {
        try {
            LogFiles.Session session = logFiles.start(category, label);
            log("Protokoll: " + session.file());
            return session;
        } catch (Exception e) {
            log("Hinweis: Protokolldatei konnte nicht angelegt werden: " + e.getMessage());
            return null;
        }
    }

    private void runTask(ThrowingRunnable task) {
        runTaskInternal(null, task);
    }

    private void runTask(LogFiles.Session session, ThrowingRunnable task) {
        runTaskInternal(session, task);
    }

    private void runTaskInternal(LogFiles.Session session, ThrowingRunnable task) {
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                if (session != null) {
                    activeLog.set(session);
                }
                try {
                    task.run();
                } catch (Exception e) {
                    String message = "FEHLER: " + e.getMessage();
                    if (session != null) {
                        session.append(message);
                    }
                    publish(message);
                } finally {
                    activeLog.remove();
                    if (session != null) {
                        session.close();
                    }
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(MainFrame.this::log);
            }

            @Override
            protected void done() {
                if (session != null) {
                    refreshLogFiles(session.file());
                }
            }
        }.execute();
    }

    private void log(String message) {
        LogFiles.Session session = activeLog.get();
        if (session != null) {
            session.append(message);
        }
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
