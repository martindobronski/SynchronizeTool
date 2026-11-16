package de.synchronizetool.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Schreibt für Paketerstellung und Paketeinspielung getrennte Protokolldateien und
 * stellt sie der Oberfläche zum Anzeigen zur Verfügung.
 *
 * <pre>
 * Programmordner/Logfiles/
 *   Erstellung/   20261116_143000_paket-1.log
 *   Einspielung/  20261116_143500_sync-paket-1.log
 * </pre>
 */
public final class LogFiles {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter READABLE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Die beiden getrennten Protokollbereiche. */
    public enum Category {
        CREATION("Paketerstellung", "Erstellung"),
        APPLY("Paketeinspielung", "Einspielung");

        private final String label;
        private final String directoryName;

        Category(String label, String directoryName) {
            this.label = label;
            this.directoryName = directoryName;
        }

        public String label() {
            return label;
        }

        public String directoryName() {
            return directoryName;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Path baseDir;

    public LogFiles(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    public static Path defaultBaseDir() {
        return ToolDirectories.logfilesDir();
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path directory(Category category) {
        return baseDir.resolve(category.directoryName());
    }

    /** Liefert die vorhandenen Protokolle eines Bereichs, neueste zuerst. */
    public List<Path> list(Category category) throws IOException {
        Path directory = directory(category);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".log"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        }
    }

    /** Beginnt ein neues Protokoll und liefert die Schreib-Sitzung. */
    public Session start(Category category, String label) throws IOException {
        Path directory = directory(category);
        Files.createDirectories(directory);

        String safeLabel = sanitize(label);
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP);
        Path file = directory.resolve(timestamp + "_" + safeLabel + ".log");
        int counter = 2;
        while (Files.exists(file)) {
            file = directory.resolve(timestamp + "_" + safeLabel + "_" + counter++ + ".log");
        }

        BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            writer.write("# SynchronizeTool – Protokoll");
            writer.newLine();
            writer.write("# Bereich: " + category.label());
            writer.newLine();
            writer.write("# Vorgang: " + (label == null ? "" : label.trim()));
            writer.newLine();
            writer.write("# Zeitpunkt: " + LocalDateTime.now().format(READABLE_TIMESTAMP));
            writer.newLine();
            writer.write("# Rechner: " + System.getProperty("user.name", "?")
                    + " / " + System.getProperty("os.name", "?"));
            writer.newLine();
            writer.write("# ------------------------------------------------------------");
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            writer.close();
            throw e;
        }
        return new Session(file, writer);
    }

    private static String sanitize(String label) {
        String value = label == null ? "" : label.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        value = value.replaceAll("^[-.]+", "").replaceAll("[-.]+$", "");
        if (value.length() > 80) {
            value = value.substring(0, 80).replaceAll("[-.]+$", "");
        }
        return value.isEmpty() ? "vorgang" : value;
    }

    /** Eine laufende Protokoll-Sitzung. Schreibfehler dürfen den Vorgang nicht abbrechen. */
    public static final class Session implements AutoCloseable {

        private final Path file;
        private final BufferedWriter writer;
        private boolean closed;

        private Session(Path file, BufferedWriter writer) {
            this.file = file;
            this.writer = writer;
        }

        public Path file() {
            return file;
        }

        public synchronized void append(String message) {
            if (closed) {
                return;
            }
            try {
                writer.write(message == null ? "" : message);
                writer.newLine();
                writer.flush();
            } catch (IOException ignored) {
                // Das UI-Protokoll bleibt verfügbar; die Datei ist nur eine Zugabe.
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                writer.write("# Ende: " + LocalDateTime.now().format(READABLE_TIMESTAMP));
                writer.newLine();
                writer.flush();
            } catch (IOException ignored) {
                // ignorieren
            }
            try {
                writer.close();
            } catch (IOException ignored) {
                // ignorieren
            }
        }
    }
}
