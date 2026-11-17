package de.synchronizetool.model;

import de.synchronizetool.core.PathMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Die Konfiguration: eine geordnete Liste der ausgewählten Dateien und Verzeichnisse. */
public final class SyncConfig {

    private final List<SyncEntry> entries = new ArrayList<>();
    private final List<String> excludes = new ArrayList<>();

    public List<SyncEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<String> excludes() {
        return Collections.unmodifiableList(excludes);
    }

    public void setExcludes(List<String> patterns) {
        excludes.clear();
        for (String pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            String trimmed = pattern.trim();
            if (!trimmed.isEmpty() && !excludes.contains(trimmed)) {
                excludes.add(trimmed);
            }
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Eintrag hinzufügen. Doppelte und Einträge, die bereits von einem ausgewählten
     * Verzeichnis abgedeckt werden, werden verworfen. Ein neues Verzeichnis entfernt
     * umgekehrt seine bereits enthaltenen Kinder.
     */
    public boolean add(SyncEntry entry) {
        if (entries.contains(entry)) {
            return false;
        }
        for (SyncEntry existing : entries) {
            if (entry.isCoveredBy(existing)) {
                return false;
            }
        }
        if (entry.kind() == SyncEntry.Kind.DIRECTORY) {
            entries.removeIf(existing -> existing.isCoveredBy(entry));
        }
        return entries.add(entry);
    }

    public void addFile(Path absolute) {
        add(SyncEntry.file(absolute));
    }

    public void addDirectory(Path absolute) {
        add(SyncEntry.directory(absolute));
    }

    public void remove(int index) {
        entries.remove(index);
    }

    public void clearEntries() {
        entries.clear();
    }

    public void save(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("# SyncTool Konfiguration v1");
            writer.newLine();
            writer.write("# F = Datei, D = Verzeichnis | " + PathMapper.HOME_TOKEN + " = Benutzerverzeichnis des Zielrechners");
            writer.newLine();
            writer.write("# X = Ausschlussmuster (*, ?, **), z. B. X|*.log oder X|node_modules");
            writer.newLine();
            for (SyncEntry entry : entries) {
                writer.write(entry.token() + "|" + entry.logicalPath());
                writer.newLine();
            }
            for (String exclude : excludes) {
                writer.write("X|" + exclude);
                writer.newLine();
            }
        }
    }

    public static SyncConfig load(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(reader, file.toString());
        }
    }

    /** Liest eine Konfiguration aus einem Stream, z. B. direkt aus einem ZIP-Paket. */
    public static SyncConfig load(InputStream in, String sourceName) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return load(reader, sourceName);
        }
    }

    private static SyncConfig load(BufferedReader reader, String sourceName) throws IOException {
        SyncConfig config = new SyncConfig();
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('|');
            if (separator < 1 || separator == trimmed.length() - 1) {
                throw new IOException("Ungültige Zeile " + lineNumber + " in " + sourceName + ": " + line);
            }
            String token = trimmed.substring(0, separator).trim();
            String path = trimmed.substring(separator + 1).trim();
            if (token.equals("X")) {
                if (!config.excludes.contains(path)) {
                    config.excludes.add(path);
                }
            } else {
                config.add(SyncEntry.of(token, path));
            }
        }
        return config;
    }
}
