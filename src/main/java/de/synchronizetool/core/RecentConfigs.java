package de.synchronizetool.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Speichert die zuletzt verwendeten Konfigurationsdateien dauerhaft auf dem Rechner,
 * damit sie nach einem Neustart wieder geladen werden können.
 */
public final class RecentConfigs {

    private static final String HEADER = "# SyncTool Konfigurationsliste v1";
    private static final int MAX_ENTRIES = 25;

    private final Path file;
    private final Consumer<String> log;
    private final List<Entry> entries = new ArrayList<>();

    public RecentConfigs(Path file, Consumer<String> log) {
        this.file = file.toAbsolutePath().normalize();
        this.log = log;
        load();
    }

    public static Path defaultFile() {
        return Paths.get(System.getProperty("user.home"), ".synchronizetool", "recent-configs.txt");
    }

    public record Entry(Path configFile, long lastUsed) {

        public Path packageDir() {
            return configFile.getParent();
        }

        @Override
        public String toString() {
            return configFile.toString();
        }
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Eintrag nach vorne holen (oder neu anlegen) und sofort speichern. */
    public synchronized void remember(Path configFile) {
        Path normalized = configFile.toAbsolutePath().normalize();
        entries.removeIf(entry -> entry.configFile().equals(normalized));
        entries.add(0, new Entry(normalized, System.currentTimeMillis()));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        save();
    }

    public synchronized void remove(Path configFile) {
        Path normalized = configFile.toAbsolutePath().normalize();
        if (entries.removeIf(entry -> entry.configFile().equals(normalized))) {
            save();
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('|');
                if (separator < 1) {
                    continue;
                }
                try {
                    String decoded = new String(
                            Base64.getUrlDecoder().decode(trimmed.substring(0, separator)),
                            StandardCharsets.UTF_8);
                    long lastUsed = Long.parseLong(trimmed.substring(separator + 1).trim());
                    Path path = Path.of(decoded);
                    if (entries.stream().noneMatch(entry -> entry.configFile().equals(path))) {
                        entries.add(new Entry(path, lastUsed));
                    }
                } catch (RuntimeException e) {
                    log.accept("Hinweis: ungültiger Eintrag in " + file + ": " + trimmed);
                }
            }
            entries.sort(Comparator.comparingLong(Entry::lastUsed).reversed());
        } catch (IOException e) {
            log.accept("Hinweis: Konfigurationsliste nicht lesbar: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                writer.write(HEADER);
                writer.newLine();
                for (Entry entry : entries) {
                    String encoded = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(entry.configFile().toString().getBytes(StandardCharsets.UTF_8));
                    writer.write(encoded + "|" + entry.lastUsed());
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            log.accept("Hinweis: Konfigurationsliste nicht speicherbar: " + e.getMessage());
        }
    }
}
