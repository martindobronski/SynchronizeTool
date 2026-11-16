package de.synchronizetool.ui;

import de.synchronizetool.core.FileOps;
import de.synchronizetool.core.LogFiles;
import de.synchronizetool.core.PackageCombiner;
import de.synchronizetool.core.PackageDownloader;
import de.synchronizetool.core.PackageServer;
import de.synchronizetool.core.PackageService;
import de.synchronizetool.core.RecentConfigs;
import de.synchronizetool.core.ToolDirectories;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Hauptfenster mit Reitern für Quelle, Ziel und Netzwerk. */
public final class MainFrame extends JFrame {

    private static final String VERSION = "0.6.0";
    private static final String RELEASE_DATE = "16.11.2026";
    private static final String VERSION_TEXT = "v." + VERSION + " vom " + RELEASE_DATE;

    private final SyncConfig sourceConfig = new SyncConfig();
    private SyncConfig targetConfig;

    private final DefaultListModel<SyncEntry> sourceModel = new DefaultListModel<>();
    private final DefaultListModel<SyncEntry> targetModel = new DefaultListModel<>();
    private final JList<SyncEntry> sourceList = new JList<>(sourceModel);
    private final JList<SyncEntry> targetList = new JList<>(targetModel);
    private final JTextField sourcePackageField = new JTextField(defaultExportPackageDir().toString());
    private final JTextField targetPackageField = new JTextField(defaultImportPackageDir().toString());
    private final JComboBox<Path> sourcePackageBox = new JComboBox<>();
    private final JComboBox<Path> targetPackageBox = new JComboBox<>();
    /** Verhindert, dass das Befüllen der Dropdowns Ladevorgänge auslöst. */
    private boolean updatingPackageBoxes;
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
        super("SynchronizeTool");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        String setupError = null;
        try {
            ToolDirectories.ensureCreated();
        } catch (Exception e) {
            setupError = e.getMessage();
        }

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Quelle: auswählen & Paket erstellen", buildSourcePanel());
        tabs.addTab("Ziel: Paket einspielen", buildTargetPanel());
        tabs.addTab("Netzwerk", buildNetworkPanel());
        tabs.addTab("Protokolle", buildLogFilesPanel());
        add(tabs, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                packageServer.stop();
            }
        });

        setSize(1040, 780);
        setLocationRelativeTo(null);
        refreshPackageBoxes();
        refreshRecentConfigs();
        refreshLogFiles(null);
        log("SynchronizeTool " + VERSION_TEXT + " gestartet.");
        log("Programmordner: " + ToolDirectories.programDir());
        log("Pakete exportieren: " + ToolDirectories.exportDir());
        log("Pakete importieren: " + ToolDirectories.importDir());
        log("Protokolle: " + ToolDirectories.logfilesDir());
        if (setupError != null) {
            log("FEHLER: Programmordner konnten nicht angelegt werden: " + setupError);
            JOptionPane.showMessageDialog(this,
                    "Die Ordner Import, Export und Logfiles konnten nicht angelegt werden:\n"
                            + setupError + "\n\nBitte das Tool in einen beschreibbaren Ordner legen.",
                    "Programmordner nicht beschreibbar", JOptionPane.ERROR_MESSAGE);
        }
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

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel packageChoice = new JPanel(new BorderLayout(6, 4));
        packageChoice.add(new JLabel("Paket in Export:"), BorderLayout.WEST);
        sourcePackageBox.setRenderer(packageBoxRenderer());
        sourcePackageBox.addActionListener(e -> selectedSourcePackageChanged());
        packageChoice.add(sourcePackageBox, BorderLayout.CENTER);
        JPanel packageButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton loadPackage = new JButton("Laden");
        loadPackage.addActionListener(e -> loadSelectedSourcePackage());
        JButton refreshPackages = new JButton("Aktualisieren");
        refreshPackages.addActionListener(e -> refreshPackageBoxes());
        packageButtons.add(loadPackage);
        packageButtons.add(refreshPackages);
        packageChoice.add(packageButtons, BorderLayout.EAST);
        top.add(packageChoice);
        top.add(Box.createVerticalStrut(4));

        JPanel fieldRow = new JPanel(new BorderLayout(6, 4));
        fieldRow.add(new JLabel("Paketverzeichnis (diesen Ordner später auf Rechner 2 kopieren):"),
                BorderLayout.NORTH);
        fieldRow.add(sourcePackageField, BorderLayout.CENTER);
        JButton choosePackage = new JButton("...");
        choosePackage.addActionListener(e -> chooseDirectory(sourcePackageField));
        fieldRow.add(choosePackage, BorderLayout.EAST);
        top.add(fieldRow);

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
        Set<Path> knownPackages = new LinkedHashSet<>();
        for (Path directory : ToolDirectories.listPackages(ToolDirectories.exportDir())) {
            Path config = directory.resolve(PackageService.CONFIG_FILE);
            if (knownPackages.add(config)) {
                availableModel.addElement(config);
            }
        }
        for (RecentConfigs.Entry entry : recentConfigs.entries()) {
            Path config = entry.configFile();
            if (isCombinablePackage(config) && knownPackages.add(config)) {
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
        gbc.gridwidth = 3;
        gbc.weightx = 1;
        form.add(new JLabel("Speicherort: " + ToolDirectories.exportDir()), gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 3;
        form.add(new JLabel("<html>Bei gleichen Zielpfaden gewinnt das <b>oberste</b> Paket. "
                + "Ausschlussmuster werden zusammengeführt.<br>Die Daten werden aus den "
                + "vorhandenen <i>payload</i>-Ordnern übernommen; die ursprünglichen Dateien "
                + "müssen nicht mehr existieren.<br>Das Gesamtpaket wird als eigener Ordner "
                + "unter <i>Export</i> angelegt.</html>"), gbc);

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

            Path target = resolveNewPackagePath(nameField, ToolDirectories.exportDir());
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
                    refreshPackageBoxes();
                    selectPackageInBoxSilently(sourcePackageBox, target);
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
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        form.add(new JLabel("Speicherort: " + ToolDirectories.exportDir()), gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        form.add(keepSelection, gbc);

        gbc.gridy = 3;
        form.add(new JLabel("<html>Das neue Paket wird unter <i>Export/Paketname</i> angelegt.<br>"
                + "Ein bereits vorhandenes Paket bleibt unverändert.</html>"), gbc);

        while (true) {
            int result = JOptionPane.showConfirmDialog(this, form, "Neues Paket anlegen",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return;
            }

            Path newPackage = resolveNewPackagePath(nameField, ToolDirectories.exportDir());
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
                    refreshPackageBoxes();
                    selectPackageInBoxSilently(sourcePackageBox, newPackage);
                    refreshRecentConfigs(configFile);
                });
                log("Neues Paket angelegt: " + newPackage);
            });
            return;
        }
    }

    /**
     * Validiert den Paketnamen und liefert den neuen Paketpfad unter {@code parent} oder
     * {@code null}, wenn eine Meldung angezeigt wurde.
     */
    private Path resolveNewPackagePath(JTextField nameField, Path parent) {
        String name = nameField.getText().trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.matches(".*[\\\\/:*?\"<>|].*")) {
            JOptionPane.showMessageDialog(this,
                    "Bitte einen gültigen Paketnamen ohne Pfadtrenner oder Sonderzeichen angeben.",
                    "Ungültiger Name", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        if (parent == null) {
            JOptionPane.showMessageDialog(this, "Bitte einen Speicherort angeben.",
                    "Fehlender Speicherort", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        Path normalizedParent = parent.toAbsolutePath().normalize();
        if (Files.exists(normalizedParent) && !Files.isDirectory(normalizedParent)) {
            JOptionPane.showMessageDialog(this, "Der Speicherort ist kein Verzeichnis.",
                    "Fehler", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        Path newPackage = normalizedParent.resolve(name).normalize();
        if (!normalizedParent.equals(newPackage.getParent())) {
            JOptionPane.showMessageDialog(this, "Ungültiger Paketname.",
                    "Fehler", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        if (Files.exists(newPackage)) {
            JOptionPane.showMessageDialog(this,
                    "Im Export-Ordner existiert bereits \"" + name + "\".\n"
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
        if (packageDir == null || requirePackageFolder(packageDir)) {
            return;
        }
        boolean replace = shouldReplacePackage(packageDir);
        if (replace && !confirmReplacePackage(packageDir)) {
            return;
        }
        runLoggedTask(LogFiles.Category.CREATION, "paket-erstellen", () -> {
            log("Paketverzeichnis: " + packageDir);
            if (replace) {
                log("Vorhandenes Paket wird vollständig ersetzt.");
                FileOps.deleteRecursively(packageDir);
            }
            service.createPackage(sourceConfig, packageDir);
            Path configFile = packageDir.resolve(PackageService.CONFIG_FILE);
            recentConfigs.remember(configFile);
            SwingUtilities.invokeLater(() -> {
                currentConfigFile = configFile;
                refreshPackageBoxes();
                selectPackageInBoxSilently(sourcePackageBox, packageDir);
                refreshRecentConfigs(configFile);
            });
        });
    }

    /** Lehnt die übergeordneten Ordner Import und Export als Paketordner ab. */
    private boolean requirePackageFolder(Path packageDir) {
        Path normalized = packageDir.toAbsolutePath().normalize();
        Path exampleRoot = null;
        if (normalized.equals(ToolDirectories.exportDir())) {
            exampleRoot = ToolDirectories.exportDir();
        } else if (normalized.equals(ToolDirectories.importDir())) {
            exampleRoot = ToolDirectories.importDir();
        }
        if (exampleRoot != null) {
            JOptionPane.showMessageDialog(this,
                    "Bitte einen Paketordner auswählen, nicht den übergeordneten Ordner \""
                            + normalized.getFileName() + "\".\n\n"
                            + "Pakete liegen zum Beispiel unter:\n" + exampleRoot
                            + File.separator + "Paket1",
                    "Paketordner erforderlich", JOptionPane.WARNING_MESSAGE);
            return true;
        }
        return false;
    }

    /** Ein vorhandenes Paket in Export wird vor dem Neuschreiben vollständig geleert. */
    private static boolean shouldReplacePackage(Path packageDir) {
        return Files.exists(packageDir)
                && (ToolDirectories.isCompletePackage(packageDir)
                        || ToolDirectories.isDirectChild(ToolDirectories.exportDir(), packageDir));
    }

    private boolean confirmReplacePackage(Path packageDir) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Das Paket\n" + packageDir + "\n\nwird vollständig geleert und neu erstellt.\n"
                        + "Dabei werden auch Dateien entfernt, die nicht mehr ausgewählt sind.\n\nFortfahren?",
                "Paket ersetzen", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.YES_OPTION;
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
                packageDir = defaultExportPackageDir();
                log("Hinweis: Die Datei heißt nicht \"" + PackageService.CONFIG_FILE
                        + "\"; als Paketverzeichnis wird " + packageDir + " verwendet.");
            }
            sourcePackageField.setText(packageDir.toString());
            selectPackageInBoxSilently(sourcePackageBox, packageDir);
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

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel packageChoice = new JPanel(new BorderLayout(6, 4));
        packageChoice.add(new JLabel("Paket in Import:"), BorderLayout.WEST);
        targetPackageBox.setRenderer(packageBoxRenderer());
        targetPackageBox.addActionListener(e -> selectedTargetPackageChanged());
        packageChoice.add(targetPackageBox, BorderLayout.CENTER);
        JPanel packageButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton refreshPackages = new JButton("Aktualisieren");
        refreshPackages.addActionListener(e -> refreshPackageBoxes());
        packageButtons.add(refreshPackages);
        packageChoice.add(packageButtons, BorderLayout.EAST);
        top.add(packageChoice);
        top.add(Box.createVerticalStrut(4));

        JPanel fieldRow = new JPanel(new BorderLayout(6, 4));
        fieldRow.add(new JLabel("Paketverzeichnis (von Rechner 1 hierher kopiert):"), BorderLayout.NORTH);
        fieldRow.add(targetPackageField, BorderLayout.CENTER);
        JButton choosePackage = new JButton("...");
        choosePackage.addActionListener(e -> chooseDirectory(targetPackageField));
        fieldRow.add(choosePackage, BorderLayout.EAST);
        top.add(fieldRow);
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
        if (packageDir == null || requirePackageFolder(packageDir)) {
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
        if (packageDir == null || requirePackageFolder(packageDir)) {
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
        if (packageDir == null || requirePackageFolder(packageDir) || !confirmSync()) {
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
        destinationRow.add(new JLabel("Paketordner (wird unter Import angelegt):"), BorderLayout.WEST);
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
        if (packageDir == null || requirePackageFolder(packageDir)) {
            return;
        }
        int port = parsePort(serverPortField.getText());
        if (port < 0) {
            return;
        }
        boolean replace = shouldReplacePackage(packageDir);
        if (replace && !confirmReplacePackage(packageDir)) {
            return;
        }
        runLoggedTask(LogFiles.Category.CREATION, "paket-freigeben", () -> {
            log("Paketverzeichnis: " + packageDir);
            log("Port: " + port);
            if (replace) {
                log("Vorhandenes Paket wird vollständig ersetzt.");
                FileOps.deleteRecursively(packageDir);
            }
            service.createPackage(sourceConfig, packageDir);
            Path configFile = packageDir.resolve(PackageService.CONFIG_FILE);
            recentConfigs.remember(configFile);
            String url = packageServer.start(packageDir, port);
            SwingUtilities.invokeLater(() -> {
                shareUrlField.setText(url);
                downloadUrlField.setText(url);
                serverStatus.setText("aktiv auf Port " + packageServer.port());
                currentConfigFile = configFile;
                refreshPackageBoxes();
                selectPackageInBoxSilently(sourcePackageBox, packageDir);
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
            JOptionPane.showMessageDialog(this, "Ungültiger Paketordner.",
                    "Fehler", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (requirePackageFolder(destination)) {
            return;
        }
        if (Files.exists(destination) && !confirmReplacePackage(destination)) {
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
                refreshPackageBoxes();
                selectPackageInBoxSilently(targetPackageBox, packageDir);
                refreshRecentConfigs(configFile);
                downloadDirField.setText(defaultDownloadDir().toString());
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

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(0, 2));
        footer.add(buildLogPanel(), BorderLayout.CENTER);

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        JLabel versionLabel = new JLabel(VERSION_TEXT);
        versionLabel.setFont(versionLabel.getFont().deriveFont(Font.PLAIN, 11f));
        statusBar.add(versionLabel);
        footer.add(statusBar, BorderLayout.SOUTH);
        return footer;
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

    private static DefaultListCellRenderer packageBoxRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Path path) {
                    Path name = path.getFileName();
                    setText(name == null ? path.toString() : name.toString());
                    setToolTipText(path.toString());
                }
                return this;
            }
        };
    }

    private void refreshPackageBoxes() {
        updatingPackageBoxes = true;
        try {
            fillPackageBox(sourcePackageBox, ToolDirectories.exportDir(), sourcePackageField.getText());
            fillPackageBox(targetPackageBox, ToolDirectories.importDir(), targetPackageField.getText());
        } finally {
            updatingPackageBoxes = false;
        }
    }

    private static void fillPackageBox(JComboBox<Path> box, Path root, String currentText) {
        box.removeAllItems();
        for (Path directory : ToolDirectories.listPackages(root)) {
            box.addItem(directory);
        }
        Path current = pathOrNull(currentText);
        if (current != null && !boxContains(box, current)) {
            box.addItem(current);
        }
        if (box.getItemCount() == 0) {
            box.addItem(root);
        }
        if (current != null) {
            selectPackageInBox(box, current);
        }
    }

    private static boolean boxContains(JComboBox<Path> box, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (int i = 0; i < box.getItemCount(); i++) {
            Path item = box.getItemAt(i);
            if (item != null && item.toAbsolutePath().normalize().equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static void selectPackageInBox(JComboBox<Path> box, Path packageDir) {
        if (packageDir == null) {
            return;
        }
        Path normalized = packageDir.toAbsolutePath().normalize();
        for (int i = 0; i < box.getItemCount(); i++) {
            Path item = box.getItemAt(i);
            if (item != null && item.toAbsolutePath().normalize().equals(normalized)) {
                box.setSelectedIndex(i);
                return;
            }
        }
    }

    private void selectPackageInBoxSilently(JComboBox<Path> box, Path packageDir) {
        boolean previous = updatingPackageBoxes;
        updatingPackageBoxes = true;
        try {
            selectPackageInBox(box, packageDir);
        } finally {
            updatingPackageBoxes = previous;
        }
    }

    private static Path pathOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Paths.get(text.trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private void selectedSourcePackageChanged() {
        if (updatingPackageBoxes) {
            return;
        }
        Object item = sourcePackageBox.getSelectedItem();
        if (!(item instanceof Path packageDir) || packageDir.equals(ToolDirectories.exportDir())) {
            return;
        }
        loadSourcePackage(packageDir);
    }

    private void loadSelectedSourcePackage() {
        Object item = sourcePackageBox.getSelectedItem();
        if (!(item instanceof Path packageDir) || requirePackageFolder(packageDir)) {
            return;
        }
        loadSourcePackage(packageDir);
    }

    private void loadSourcePackage(Path packageDir) {
        Path configFile = packageDir.resolve(PackageService.CONFIG_FILE);
        if (!Files.isRegularFile(configFile)) {
            JOptionPane.showMessageDialog(this,
                    "Im Paketordner wurde keine " + PackageService.CONFIG_FILE + " gefunden:\n" + packageDir,
                    "Kein vollständiges Paket", JOptionPane.ERROR_MESSAGE);
            return;
        }
        loadConfigFile(configFile);
    }

    private void selectedTargetPackageChanged() {
        if (updatingPackageBoxes) {
            return;
        }
        Object item = targetPackageBox.getSelectedItem();
        if (!(item instanceof Path packageDir) || packageDir.equals(ToolDirectories.importDir())) {
            return;
        }
        targetPackageField.setText(packageDir.toString());
        previewTargetConfig();
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

    private static Path defaultExportPackageDir() {
        return defaultPackageIn(ToolDirectories.exportDir());
    }

    private static Path defaultImportPackageDir() {
        return defaultPackageIn(ToolDirectories.importDir());
    }

    private static Path defaultPackageIn(Path root) {
        List<Path> packages = ToolDirectories.listPackages(root);
        return packages.isEmpty() ? root : packages.get(0);
    }

    private static Path defaultDownloadDir() {
        return ToolDirectories.importDir().resolve(
                "Netzwerkpaket-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
