package de.synchronizetool.core;

import de.synchronizetool.model.SyncConfig;
import de.synchronizetool.model.SyncEntry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Erzeugt ein transportables Paket, vergleicht es mit dem Ziel (Dry Run) und spielt es
 * auf einem anderen Rechner wieder ein.
 *
 * <pre>
 * Paket/
 *   config.txt            Liste der logischen Pfade und Ausschlussmuster
 *   payload/              Kopie der Daten, Struktur entspricht den logischen Pfaden
 * </pre>
 */
public final class PackageService {

    public static final String CONFIG_FILE = "config.txt";
    public static final String PAYLOAD_DIR = "payload";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Pattern BACKUP_NAME =
            Pattern.compile(".*_\\d{8}_\\d{6}(_\\d+)?");
    private static final StandardCopyOption[] COPY_OPTIONS = {
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES
    };
    private static final int MAX_DETAIL_LINES = 20;

    /** Wie das Paket auf dem Zielrechner eingespielt wird. */
    public enum CopyMode {
        /** Nur neue und geänderte Dateien schreiben, nicht mehr vorhandene Dateien sichern. */
        CHANGED_ONLY("Nur geänderte Dateien"),
        /** Jeden Eintrag vollständig sichern und neu kopieren. */
        REPLACE_ALL("Alles ersetzen");

        private final String label;

        CopyMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Consumer<String> log;
    private final RollbackService rollback;
    private boolean attributeWarningLogged;

    public PackageService(Consumer<String> log) {
        this(log, new RollbackService(log));
    }

    public PackageService(Consumer<String> log, RollbackService rollback) {
        this.log = log;
        this.rollback = rollback;
    }

    // ------------------------------------------------------------------ Quelle

    /** Kopiert alle ausgewählten Daten in das Paketverzeichnis und schreibt die config.txt. */
    public void createPackage(SyncConfig config, Path packageDir) throws IOException {
        log.accept("Paket erstellen: " + packageDir);
        Path payload = packageDir.resolve(PAYLOAD_DIR);
        Files.createDirectories(payload);
        Path normalizedPayload = payload.toAbsolutePath().normalize();
        ExcludeMatcher matcher = new ExcludeMatcher(config.excludes());

        if (config.isEmpty()) {
            log.accept("Hinweis: Die Auswahl ist leer.");
        }
        if (!matcher.isEmpty()) {
            log.accept("Ausschlüsse aktiv (" + config.excludes().size() + "): " + config.excludes());
        }

        for (SyncEntry entry : config.entries()) {
            Path source = entry.localPath();
            if (!Files.exists(source)) {
                log.accept("Übersprungen, nicht gefunden: " + source);
                continue;
            }
            boolean expectedType = entry.kind() == SyncEntry.Kind.DIRECTORY
                    ? Files.isDirectory(source) : Files.isRegularFile(source);
            if (!expectedType) {
                log.accept("Übersprungen, falscher Typ: " + source);
                continue;
            }
            // Falls der ausgewählte Pfad selbst ein symbolischer Link ist, dessen Ziel kopieren.
            Path readable = source.toRealPath();

            Path destination = safeResolve(normalizedPayload, entry.payloadRelative());
            Files.createDirectories(destination.getParent());
            FileOps.deleteRecursively(destination); // altes Snapshot vollständig ersetzen

            if (entry.kind() == SyncEntry.Kind.DIRECTORY) {
                copyTree(readable, destination, matcher);
            } else {
                copyFile(readable, destination);
            }
            log.accept("Gepackt: " + source);
        }

        Path configFile = packageDir.resolve(CONFIG_FILE);
        config.save(configFile);
        log.accept("Konfiguration gespeichert: " + configFile);
        log.accept("Paket fertig: " + packageDir);
    }

    // ------------------------------------------------------------------- Ziel

    /** Liest die config.txt aus einem Paketordner oder direkt aus einer Paket-ZIP. */
    public SyncConfig loadConfig(Path packagePath) throws IOException {
        SyncConfig config;
        String source;
        if (PackageArchive.isArchive(packagePath)) {
            config = PackageArchive.readConfig(packagePath);
            source = packagePath + "!/" + CONFIG_FILE;
        } else {
            Path configFile = packagePath.resolve(CONFIG_FILE);
            if (!Files.isRegularFile(configFile)) {
                throw new IOException("Keine " + CONFIG_FILE + " gefunden in: " + packagePath);
            }
            config = SyncConfig.load(configFile);
            source = configFile.toString();
        }
        log.accept("Konfiguration geladen: " + source + " (" + config.entries().size() + " Einträge)");
        for (SyncEntry entry : config.entries()) {
            log.accept("  " + entry + " -> " + entry.localPath());
        }
        if (!config.excludes().isEmpty()) {
            log.accept("  Ausschlüsse: " + config.excludes());
        }
        return config;
    }

    /**
     * Vergleicht das Paket mit dem Ziel, ohne etwas zu verändern. Bei einer ZIP-Datei
     * wird das Paket vorher in einen temporären Ordner entpackt.
     */
    public void dryRun(SyncConfig config, Path packagePath) throws IOException {
        if (!PackageArchive.isArchive(packagePath)) {
            dryRunDirectory(config, packagePath);
            return;
        }
        Path temp = PackageArchive.extractToTemp(packagePath);
        try {
            log.accept("Paket entpackt: " + packagePath + " -> " + temp);
            dryRunDirectory(config, temp);
        } finally {
            FileOps.deleteRecursively(temp);
        }
    }

    private void dryRunDirectory(SyncConfig config, Path packageDir) throws IOException {
        Path payload = requirePayload(packageDir);
        ExcludeMatcher matcher = new ExcludeMatcher(config.excludes());
        log.accept("VORSCHAU (Dry Run) – es werden keine Dateien verändert.");
        log.accept("Ziel-Benutzerverzeichnis: " + System.getProperty("user.home"));

        int changed = 0;
        int same = 0;
        int problems = 0;
        int backups = 0;

        for (SyncEntry entry : config.entries()) {
            Path source = safeResolve(payload, entry.payloadRelative());
            Path target = entry.localPath();
            boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (targetExists) {
                backups++;
            }
            if (!Files.exists(source)) {
                problems++;
                log.accept("PROBLEM   Daten fehlen im Paket: " + source);
                continue;
            }

            if (entry.kind() == SyncEntry.Kind.FILE) {
                if (!targetExists) {
                    changed++;
                    log.accept("NEU       " + target);
                } else if (Files.isDirectory(target)) {
                    changed++;
                    log.accept("ÄNDERN    " + target + "  (Verzeichnis wird durch Datei ersetzt)");
                } else if (sameFile(source, target)) {
                    same++;
                    log.accept("GLEICH    " + target);
                } else {
                    changed++;
                    log.accept("ÄNDERN    " + target);
                }
                continue;
            }

            Map<String, String> payloadFiles = fingerprints(source, matcher);
            Map<String, String> targetFiles = Files.isDirectory(target)
                    ? fingerprints(target, matcher) : new TreeMap<>();

            List<String> added = new ArrayList<>();
            List<String> modified = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            for (Map.Entry<String, String> file : payloadFiles.entrySet()) {
                String oldHash = targetFiles.get(file.getKey());
                if (oldHash == null) {
                    added.add(file.getKey());
                } else if (!oldHash.equals(file.getValue())) {
                    modified.add(file.getKey());
                }
            }
            for (String relative : targetFiles.keySet()) {
                if (!payloadFiles.containsKey(relative)) {
                    removed.add(relative);
                }
            }

            if (!targetExists) {
                changed++;
                log.accept("NEU       " + target + "  (+" + added.size() + " Dateien)");
                logDetails("+", added);
            } else if (!Files.isDirectory(target)) {
                changed++;
                log.accept("ÄNDERN    " + target + "  (Datei wird durch Verzeichnis ersetzt)");
            } else if (added.isEmpty() && modified.isEmpty() && removed.isEmpty()) {
                same++;
                log.accept("GLEICH    " + target + "  (" + payloadFiles.size() + " Dateien)");
            } else {
                changed++;
                log.accept("ÄNDERN    " + target + "  (+" + added.size() + " ~" + modified.size()
                        + " -" + removed.size() + ")");
                logDetails("+", added);
                logDetails("~", modified);
                logDetails("-", removed);
            }
        }

        log.accept("Vorschau beendet: " + changed + " Einträge mit Änderungen, " + same
                + " unverändert, " + problems + " Probleme, " + backups + " Sicherungen.");
        log.accept("Es wurde nichts verändert. Zum Ausführen \"Synchronisieren\" wählen.");
    }

    /**
     * Spielt das Paket auf diesem Rechner ein. Ohne Angabe eines Modus wird wie bisher
     * vollständig ersetzt.
     */
    public void apply(SyncConfig config, Path packageDir) throws IOException {
        apply(config, packageDir, CopyMode.REPLACE_ALL);
    }

    /**
     * Spielt das Paket auf diesem Rechner ein. Bei einer ZIP-Datei wird das Paket vorher
     * in einen temporären Ordner entpackt.
     *
     * <p>Bei {@link CopyMode#REPLACE_ALL} wird jeder Eintrag als Ganzes gesichert und neu
     * kopiert. Bei {@link CopyMode#CHANGED_ONLY} werden nur neue und geänderte Dateien
     * geschrieben, unveränderte Dateien übersprungen und nicht mehr vorhandene Dateien
     * mit Sicherung entfernt. Ausschlussmuster bleiben in beiden Modi unangetastet.</p>
     */
    public void apply(SyncConfig config, Path packagePath, CopyMode mode) throws IOException {
        if (!PackageArchive.isArchive(packagePath)) {
            applyDirectory(config, packagePath, mode);
            return;
        }
        Path temp = PackageArchive.extractToTemp(packagePath);
        try {
            log.accept("Paket entpackt: " + packagePath + " -> " + temp);
            applyDirectory(config, temp, mode);
        } finally {
            FileOps.deleteRecursively(temp);
        }
    }

    private void applyDirectory(SyncConfig config, Path packageDir, CopyMode mode) throws IOException {
        Path payload = requirePayload(packageDir);
        ExcludeMatcher matcher = new ExcludeMatcher(config.excludes());
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        log.accept("Einspielen gestartet, Sicherungs-Timestamp: " + timestamp);
        log.accept("Kopiermodus: " + mode.label());

        RollbackService.Recorder recorder = null;
        try {
            recorder = rollback.begin();
        } catch (IOException e) {
            log.accept("Hinweis: Rollback-Protokoll konnte nicht angelegt werden: " + e.getMessage());
        }

        ApplyContext context = new ApplyContext(recorder, timestamp, matcher);
        int ok = 0;
        int failed = 0;
        try {
            for (SyncEntry entry : config.entries()) {
                Path source = safeResolve(payload, entry.payloadRelative());
                Path target = entry.localPath();
                try {
                    if (!Files.exists(source)) {
                        throw new IOException("Daten fehlen im Paket: " + source);
                    }
                    if (mode == CopyMode.REPLACE_ALL) {
                        applyReplaceAll(entry, source, target, context);
                    } else if (entry.kind() == SyncEntry.Kind.FILE) {
                        applyChangedFile(source, target, context);
                    } else {
                        applyChangedDirectory(source, target, context);
                    }

                    if (matchesEntry(entry, source, target, matcher)) {
                        ok++;
                        log.accept("OK        " + target);
                    } else {
                        failed++;
                        log.accept("PRÜFEN    " + target + " (Unterschiede nach dem Kopieren)");
                    }
                } catch (Exception e) {
                    failed++;
                    log.accept("FEHLER    " + target + ": " + e.getMessage());
                }
            }
        } finally {
            if (recorder != null) {
                try {
                    recorder.close();
                } catch (IOException e) {
                    log.accept("Hinweis: Rollback-Protokoll konnte nicht geschlossen werden: " + e.getMessage());
                }
            }
        }
        log.accept("Einspielen beendet: " + ok + " ok, " + failed + " mit Hinweis/Fehler.");
        if (ok > 0) {
            log.accept("Rollback möglich mit \"Letzte Synchronisation rückgängig machen\".");
        }
    }

    /** Bisheriges Verhalten: Eintrag komplett sichern und neu kopieren. */
    private void applyReplaceAll(SyncEntry entry, Path source, Path target, ApplyContext context)
            throws IOException {
        Path backup = null;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            backup = FileOps.uniqueSibling(target, context.timestamp);
            Files.move(target, backup);
            context.recordRestore(target, backup);
            log.accept("Sicherung: " + target + "  ->  " + backup.getFileName());
        } else {
            context.recordDelete(target);
        }

        if (entry.kind() == SyncEntry.Kind.DIRECTORY) {
            Files.createDirectories(target);
            copyTree(source, target);
            if (backup != null) {
                restoreExcluded(backup, target, context.matcher);
            }
        } else {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            copyFile(source, target);
        }
    }

    /** Inkrementell: einzelne Datei nur kopieren, wenn sie neu oder anders ist. */
    private void applyChangedFile(Path source, Path target, ApplyContext context) throws IOException {
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists) {
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && sameFile(source, target)) {
                log.accept("GLEICH    " + target);
                return;
            }
            Path backup = FileOps.uniqueSibling(target, context.timestamp);
            Files.move(target, backup);
            context.recordRestore(target, backup);
            log.accept("Sicherung: " + target + "  ->  " + backup.getFileName());
        }
        ensureDirectory(target.getParent(), context);
        if (!exists) {
            context.recordDelete(target);
        }
        copyFile(source, target);
        log.accept("KOPIERT   " + target);
    }

    /** Inkrementell: Verzeichnis dateiweise abgleichen, Überzähliges sichern und entfernen. */
    private void applyChangedDirectory(Path source, Path target, ApplyContext context) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Path backup = FileOps.uniqueSibling(target, context.timestamp);
            Files.move(target, backup);
            context.recordRestore(target, backup);
            log.accept("Sicherung: " + target + "  ->  " + backup.getFileName());
        }
        ensureDirectory(target, context);

        // Zuerst Überzähliges entfernen, damit neu angelegte Sicherungen nicht als
        // überzählig gelten und keine Verzeichnisse mit frischen Sicherungen verschoben werden.
        int removed = removeExtraEntries(source, target, context);
        TreeStats stats = syncTree(source, target, context);
        if (stats.copied + removed == 0) {
            log.accept("GLEICH    " + target + "  (" + stats.unchanged + " Dateien unverändert)");
        } else {
            log.accept("ABGLEICH  " + target + "  (" + stats.copied + " kopiert, "
                    + stats.unchanged + " unverändert, " + removed + " entfernt)");
        }
    }

    /** Kopiert fehlende/geänderte Dateien aus dem Payload in das bestehende Zielverzeichnis. */
    private TreeStats syncTree(Path source, Path target, ApplyContext context) throws IOException {
        int[] copied = {0};
        int[] unchanged = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (!relative.toString().isEmpty() && context.matcher.matches(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path destination = target.resolve(relative.toString());
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                    Path backup = FileOps.uniqueSibling(destination, context.timestamp);
                    Files.move(destination, backup);
                    context.recordRestore(destination, backup);
                    log.accept("Sicherung: " + destination + "  ->  " + backup.getFileName());
                }
                ensureDirectory(destination, context);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (context.matcher.matches(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.isSymbolicLink()) {
                    log.accept("Hinweis: symbolischer Link übersprungen: " + file);
                    return FileVisitResult.CONTINUE;
                }
                Path destination = target.resolve(relative.toString());
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                        Path backup = FileOps.uniqueSibling(destination, context.timestamp);
                        Files.move(destination, backup);
                        context.recordRestore(destination, backup);
                        log.accept("Sicherung: " + destination + "  ->  " + backup.getFileName());
                    } else if (sameFile(file, destination)) {
                        unchanged[0]++;
                        return FileVisitResult.CONTINUE;
                    } else {
                        Path backup = FileOps.uniqueSibling(destination, context.timestamp);
                        Files.move(destination, backup);
                        context.recordRestore(destination, backup);
                        log.accept("Sicherung: " + destination + "  ->  " + backup.getFileName());
                    }
                } else {
                    ensureDirectory(destination.getParent(), context);
                    context.recordDelete(destination);
                }
                copyFile(file, destination);
                copied[0]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                log.accept("Hinweis: übersprungen " + file + " (" + e.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }
        });
        return new TreeStats(copied[0], unchanged[0]);
    }

    /**
     * Entfernt Dateien und leere Verzeichnisse, die im Ziel vorhanden sind, aber nicht
     * im Payload. Vorher werden sie mit Timestamp gesichert, damit der Rollback sie
     * zurückholen kann. Ausgeschlossene Pfade bleiben unangetastet.
     */
    private int removeExtraEntries(Path source, Path target, ApplyContext context) throws IOException {
        Set<Path> expected = new LinkedHashSet<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path relative = source.relativize(dir);
                if (!relative.toString().isEmpty() && context.matcher.matches(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                expected.add(relative);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = source.relativize(file);
                if (!context.matcher.matches(relative) && !attrs.isSymbolicLink()) {
                    expected.add(relative);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<Path> existing;
        try (var walk = Files.walk(target)) {
            existing = walk.toList();
        }

        List<Path> removedRoots = new ArrayList<>();
        int removed = 0;
        for (Path path : existing) {
            Path relative = target.relativize(path);
            if (relative.toString().isEmpty() || context.matcher.matches(relative)) {
                continue;
            }
            if (hasBackupComponent(relative)) {
                continue; // Sicherung eines früheren Laufs nicht erneut verschieben
            }
            if (expected.contains(relative)) {
                continue;
            }
            if (removedRoots.stream().anyMatch(root -> path.startsWith(root) && !path.equals(root))) {
                continue; // liegt in einem bereits entfernten Verzeichnis
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    && containsExcluded(path, target, context.matcher)) {
                continue; // ausgeschlossene Inhalte bleiben; Kinder werden einzeln geprüft
            }
            Path backup = FileOps.uniqueSibling(path, context.timestamp);
            Files.move(path, backup);
            context.recordRestore(path, backup);
            log.accept("ENTFERNT  " + path + "  ->  " + backup.getFileName());
            removedRoots.add(path);
            removed++;
        }
        return removed;
    }

    /** Prüft, ob unterhalb von {@code directory} ein ausgeschlossener Pfad liegt. */
    private boolean containsExcluded(Path directory, Path target, ExcludeMatcher matcher) throws IOException {
        try (var walk = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path relative = target.relativize(path);
                if (!relative.toString().isEmpty() && matcher.matches(relative)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Legt ein Verzeichnis samt fehlender Eltern an und merkt neu angelegte Pfade für den Rollback. */
    private void ensureDirectory(Path directory, ApplyContext context) throws IOException {
        if (directory == null || Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path parent = directory.getParent();
        if (parent != null && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            ensureDirectory(parent, context);
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Pfad ist eine Datei, Verzeichnis erwartet: " + directory);
            }
            return;
        }
        Files.createDirectories(directory);
        context.recordDelete(directory);
        log.accept("Verzeichnis angelegt: " + directory);
    }

    private record TreeStats(int copied, int unchanged) {
    }

    /** Merkt sich Sicherungen und neu angelegte Pfade für das Rollback-Protokoll. */
    private final class ApplyContext {

        private final RollbackService.Recorder recorder;
        private final String timestamp;
        private final ExcludeMatcher matcher;
        private final Set<Path> recordedRestores = new HashSet<>();
        private final Set<Path> recordedDeletes = new HashSet<>();

        private ApplyContext(RollbackService.Recorder recorder, String timestamp, ExcludeMatcher matcher) {
            this.recorder = recorder;
            this.timestamp = timestamp;
            this.matcher = matcher;
        }

        private void recordRestore(Path target, Path backup) {
            if (recordedRestores.add(target)) {
                PackageService.this.recordRestore(recorder, target, backup);
            }
        }

        private void recordDelete(Path target) {
            if (recordedDeletes.add(target)) {
                PackageService.this.recordDelete(recorder, target);
            }
        }
    }

    public boolean hasRollback() {
        return rollback.hasManifest();
    }

    public void rollbackLastSync() throws IOException {
        rollback.rollback();
    }

    // ----------------------------------------------------------------- Hilfen

    private Path requirePayload(Path packageDir) throws IOException {
        Path payload = packageDir.resolve(PAYLOAD_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(payload)) {
            throw new IOException("Kein " + PAYLOAD_DIR + "-Verzeichnis gefunden in: " + packageDir);
        }
        return payload;
    }

    private void recordRestore(RollbackService.Recorder recorder, Path target, Path backup) {
        if (recorder == null) {
            return;
        }
        try {
            recorder.recordRestore(target, backup);
        } catch (IOException e) {
            log.accept("Hinweis: Rollback-Eintrag fehlgeschlagen: " + e.getMessage());
        }
    }

    private void recordDelete(RollbackService.Recorder recorder, Path target) {
        if (recorder == null) {
            return;
        }
        try {
            recorder.recordDelete(target);
        } catch (IOException e) {
            log.accept("Hinweis: Rollback-Eintrag fehlgeschlagen: " + e.getMessage());
        }
    }

    private Path safeResolve(Path root, String relative) throws IOException {
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root)) {
            throw new IOException("Pfad außerhalb des Pakets: " + relative);
        }
        return result;
    }

    private void copyTree(Path source, Path target) throws IOException {
        copyTree(source, target, ExcludeMatcher.empty());
    }

    private void copyTree(Path source, Path target, ExcludeMatcher matcher) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (!relative.toString().isEmpty() && matcher.matches(relative)) {
                    log.accept("Ausgeschlossen: " + dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative.toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (matcher.matches(relative)) {
                    log.accept("Ausgeschlossen: " + file);
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.isSymbolicLink()) {
                    log.accept("Hinweis: symbolischer Link übersprungen: " + file);
                    return FileVisitResult.CONTINUE;
                }
                Path destination = target.resolve(relative.toString());
                Path parent = destination.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                copyFile(file, destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                log.accept("Hinweis: übersprungen " + file + " (" + e.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Holt ausgeschlossene Dateien aus der Sicherung zurück, damit sie im Ziel erhalten bleiben. */
    private void restoreExcluded(Path backup, Path target, ExcludeMatcher matcher) throws IOException {
        if (matcher.isEmpty() || !Files.isDirectory(backup)) {
            return;
        }
        int[] count = {0};
        Files.walkFileTree(backup, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = backup.relativize(dir);
                if (!relative.toString().isEmpty() && matcher.matches(relative)) {
                    Files.createDirectories(target.resolve(relative.toString()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = backup.relativize(file);
                if (matcher.matches(relative)) {
                    Path destination = target.resolve(relative.toString());
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    copyFile(file, destination);
                    count[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (count[0] > 0) {
            log.accept("Ausgeschlossene Dateien im Ziel erhalten: " + count[0]);
        }
    }

    /** Kopiert eine Datei; auf Dateisystemen ohne Attributunterstützung (z. B. FAT32) ohne Attribute. */
    private void copyFile(Path source, Path target) throws IOException {
        try {
            Files.copy(source, target, COPY_OPTIONS);
        } catch (IOException e) {
            if (!attributeWarningLogged) {
                attributeWarningLogged = true;
                log.accept("Hinweis: Datei-Attribute können hier nicht übernommen werden, kopiere nur Inhalte.");
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean matchesEntry(SyncEntry entry, Path source, Path target, ExcludeMatcher matcher)
            throws IOException {
        if (entry.kind() == SyncEntry.Kind.FILE) {
            return sameFile(source, target);
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return fingerprints(source, matcher).equals(fingerprints(target, matcher));
    }

    private boolean sameFile(Path source, Path target) throws IOException {
        return Files.isRegularFile(target)
                && Files.size(source) == Files.size(target)
                && sha256(source).equals(sha256(target));
    }

    /** Relativer Pfad (mit /) + Hash jeder Datei, sortiert. */
    private Map<String, String> fingerprints(Path root) throws IOException {
        return fingerprints(root, ExcludeMatcher.empty(), true);
    }

    /** Wie {@link #fingerprints(Path)}, ignoriert aber ausgeschlossene Pfade. */
    private Map<String, String> fingerprints(Path root, ExcludeMatcher matcher) throws IOException {
        return fingerprints(root, matcher, true);
    }

    /**
     * Relativer Pfad (mit /) + Hash jeder Datei, sortiert. Mit {@code ignoreBackups}
     * werden Sicherungen des Werkzeugs (Name mit Timestamp) nicht mitgezählt.
     */
    private Map<String, String> fingerprints(Path root, ExcludeMatcher matcher, boolean ignoreBackups)
            throws IOException {
        Map<String, String> result = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path relative = root.relativize(dir);
                if (!relative.toString().isEmpty()
                        && (matcher.matches(relative) || (ignoreBackups && isBackupName(dir)))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = root.relativize(file);
                if (matcher.matches(relative) || (ignoreBackups && isBackupName(file))) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.isRegularFile()) {
                    String key = relative.toString().replace('\\', '/');
                    result.put(key, sha256(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static boolean isBackupName(Path path) {
        Path name = path.getFileName();
        return name != null && BACKUP_NAME.matcher(name.toString()).matches();
    }

    private static boolean hasBackupComponent(Path relative) {
        for (Path part : relative) {
            if (isBackupName(part)) {
                return true;
            }
        }
        return false;
    }

    private void logDetails(String prefix, List<String> items) {
        int shown = 0;
        for (String item : items) {
            if (shown >= MAX_DETAIL_LINES) {
                log.accept("      ... und " + (items.size() - MAX_DETAIL_LINES) + " weitere");
                return;
            }
            log.accept("      " + prefix + " " + item);
            shown++;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                byte[] buffer = new byte[8192];
                while (in.read(buffer) != -1) {
                    // nur lesen, der Digest wird dabei automatisch aktualisiert
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 nicht verfügbar", e);
        }
    }
}
