package de.synchronizetool.core;

import de.synchronizetool.model.SyncConfig;
import de.synchronizetool.model.SyncEntry;

import java.io.File;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

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
    private static final StandardCopyOption[] COPY_OPTIONS = {
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES
    };
    private static final int MAX_DETAIL_LINES = 20;

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

    /** Liest die config.txt aus einem Paket. */
    public SyncConfig loadConfig(Path packageDir) throws IOException {
        Path configFile = packageDir.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(configFile)) {
            throw new IOException("Keine " + CONFIG_FILE + " gefunden in: " + packageDir);
        }
        SyncConfig config = SyncConfig.load(configFile);
        log.accept("Konfiguration geladen: " + configFile + " (" + config.entries().size() + " Einträge)");
        for (SyncEntry entry : config.entries()) {
            log.accept("  " + entry + " -> " + entry.localPath());
        }
        if (!config.excludes().isEmpty()) {
            log.accept("  Ausschlüsse: " + config.excludes());
        }
        return config;
    }

    /**
     * Vergleicht das Paket mit dem Ziel, ohne etwas zu verändern. Zeigt neue, geänderte
     * und zu entfernende Dateien sowie die anfallenden Sicherungen.
     */
    public void dryRun(SyncConfig config, Path packageDir) throws IOException {
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

            Map<String, String> payloadFiles = fingerprints(source);
            Map<String, String> targetFiles = Files.isDirectory(target)
                    ? fingerprints(target) : new TreeMap<>();
            if (!matcher.isEmpty()) {
                targetFiles.keySet().removeIf(relative -> matcher.matches(toPath(relative)));
            }

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
     * Spielt das Paket auf diesem Rechner ein: erst Sicherung mit Timestamp, dann Kopie,
     * danach Prüfung per SHA-256. Ausschlussmuster werden im Ziel unangetastet gelassen.
     */
    public void apply(SyncConfig config, Path packageDir) throws IOException {
        Path payload = requirePayload(packageDir);
        ExcludeMatcher matcher = new ExcludeMatcher(config.excludes());
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        log.accept("Einspielen gestartet, Sicherungs-Timestamp: " + timestamp);

        RollbackService.Recorder recorder = null;
        try {
            recorder = rollback.begin();
        } catch (IOException e) {
            log.accept("Hinweis: Rollback-Protokoll konnte nicht angelegt werden: " + e.getMessage());
        }

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
                    Path backup = null;
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        backup = FileOps.uniqueSibling(target, timestamp);
                        Files.move(target, backup);
                        recordRestore(recorder, target, backup);
                        log.accept("Sicherung: " + target + "  ->  " + backup.getFileName());
                    } else {
                        recordDelete(recorder, target);
                    }

                    if (entry.kind() == SyncEntry.Kind.DIRECTORY) {
                        Files.createDirectories(target);
                        copyTree(source, target);
                        if (backup != null) {
                            restoreExcluded(backup, target, matcher);
                        }
                    } else {
                        Path parent = target.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        copyFile(source, target);
                    }

                    if (matches(entry, source, target, matcher)) {
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

    private boolean matches(SyncEntry entry, Path source, Path target, ExcludeMatcher matcher) throws IOException {
        if (entry.kind() == SyncEntry.Kind.FILE) {
            return sameFile(source, target);
        }
        if (!Files.isDirectory(target)) {
            return false;
        }
        Map<String, String> expected = fingerprints(source);
        Map<String, String> actual = fingerprints(target);
        for (Map.Entry<String, String> file : expected.entrySet()) {
            if (!file.getValue().equals(actual.get(file.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private boolean sameFile(Path source, Path target) throws IOException {
        return Files.isRegularFile(target)
                && Files.size(source) == Files.size(target)
                && sha256(source).equals(sha256(target));
    }

    /** Relativer Pfad (mit /) + Hash jeder Datei, sortiert. */
    private Map<String, String> fingerprints(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    result.put(relative, sha256(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static Path toPath(String relative) {
        return Path.of(relative.replace('/', File.separatorChar));
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
