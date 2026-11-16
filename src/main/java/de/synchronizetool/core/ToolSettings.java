package de.synchronizetool.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Speichert lokale Einstellungen des Werkzeugs dauerhaft, damit sie nach einem Neustart
 * erhalten bleiben.
 */
public final class ToolSettings {

    private static final String HEADER = "# SyncTool Einstellungen v1";
    private static final String COPY_MODE_KEY = "copyMode=";

    private final Path file;
    private PackageService.CopyMode copyMode = PackageService.CopyMode.CHANGED_ONLY;

    public ToolSettings(Path file) {
        this.file = file.toAbsolutePath().normalize();
        load();
    }

    public static Path defaultFile() {
        return Paths.get(System.getProperty("user.home"), ".synchronizetool", "settings.txt");
    }

    public PackageService.CopyMode copyMode() {
        return copyMode;
    }

    public void setCopyMode(PackageService.CopyMode mode) {
        if (mode != null && mode != copyMode) {
            copyMode = mode;
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
                if (trimmed.startsWith(COPY_MODE_KEY)) {
                    try {
                        copyMode = PackageService.CopyMode.valueOf(
                                trimmed.substring(COPY_MODE_KEY.length()).trim());
                    } catch (IllegalArgumentException ignored) {
                        // Unbekannter Wert: Standard beibehalten.
                    }
                }
            }
        } catch (IOException ignored) {
            // Nicht lesbare Einstellungen: Standardwerte verwenden.
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
                writer.write(COPY_MODE_KEY + copyMode.name());
                writer.newLine();
            }
        } catch (IOException ignored) {
            // Einstellungen sind Komfort; ein Schreibfehler darf die Anwendung nicht stoppen.
        }
    }
}
