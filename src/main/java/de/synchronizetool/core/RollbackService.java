package de.synchronizetool.core;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/**
 * Protokolliert die beim Einspielen angelegten Sicherungen und kann den letzten Lauf
 * zurückspielen. Das Protokoll liegt lokal auf dem Zielrechner, weil die Sicherungen
 * dort liegen.
 */
public final class RollbackService {

    private static final String HEADER = "# SyncTool Rollback v1";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Consumer<String> log;
    private final Path manifest;

    public RollbackService(Consumer<String> log) {
        this(log, defaultManifest());
    }

    public RollbackService(Consumer<String> log, Path manifest) {
        this.log = log;
        this.manifest = manifest.toAbsolutePath().normalize();
    }

    public static Path defaultManifest() {
        return Paths.get(System.getProperty("user.home"), ".synchronizetool", "last-sync.txt");
    }

    public Path manifest() {
        return manifest;
    }

    public boolean hasManifest() {
        return Files.isRegularFile(manifest);
    }

    public Recorder begin() throws IOException {
        Path parent = manifest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        BufferedWriter writer = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        writer.write(HEADER);
        writer.newLine();
        writer.write("# " + LocalDateTime.now());
        writer.newLine();
        writer.flush();
        return new Recorder(writer);
    }

    /** Spielt den letzten Lauf zurück: Sicherungen wiederherstellen, neue Dateien zur Seite legen. */
    public void rollback() throws IOException {
        if (!hasManifest()) {
            throw new IOException("Keine Rollback-Datei vorhanden: " + manifest);
        }
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        int restored = 0;
        int setAside = 0;
        int failed = 0;

        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            try {
                if (parts.length == 3 && parts[0].equals("R")) {
                    Path target = decode(parts[1]);
                    Path backup = decode(parts[2]);
                    if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Sicherung fehlt: " + backup);
                    }
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        Path aside = FileOps.uniqueSibling(target, "rollback_" + timestamp);
                        Files.move(target, aside);
                        setAside++;
                        log.accept("Aktueller Stand zur Seite gelegt: " + aside);
                    }
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.move(backup, target);
                    restored++;
                    log.accept("Wiederhergestellt: " + target);
                } else if (parts.length == 2 && parts[0].equals("D")) {
                    Path target = decode(parts[1]);
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        Path aside = FileOps.uniqueSibling(target, "rollback_" + timestamp);
                        Files.move(target, aside);
                        setAside++;
                        log.accept("Neu angelegten Stand zur Seite gelegt: " + aside);
                    }
                } else {
                    throw new IOException("Unbekannte Rollback-Zeile: " + line);
                }
            } catch (Exception e) {
                failed++;
                log.accept("FEHLER beim Zurückspielen: " + e.getMessage());
            }
        }

        Path done = manifest.resolveSibling(manifest.getFileName() + ".done_" + timestamp);
        Files.move(manifest, done);
        log.accept("Rollback beendet: " + restored + " wiederhergestellt, " + setAside
                + " zur Seite gelegt, " + failed + " Fehler.");
        log.accept("Rollback-Protokoll archiviert: " + done);
    }

    private static String encode(Path path) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Path decode(String value) {
        return Path.of(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
    }

    /** Schreibt die Sicherungszuordnungen während eines Einspiel-Laufs. */
    public final class Recorder implements Closeable {

        private final BufferedWriter writer;

        private Recorder(BufferedWriter writer) {
            this.writer = writer;
        }

        public void recordRestore(Path target, Path backup) throws IOException {
            write("R|" + encode(target) + "|" + encode(backup));
        }

        public void recordDelete(Path target) throws IOException {
            write("D|" + encode(target));
        }

        private void write(String line) throws IOException {
            writer.write(line);
            writer.newLine();
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
