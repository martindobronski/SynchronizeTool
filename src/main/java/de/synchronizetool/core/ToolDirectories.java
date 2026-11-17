package de.synchronizetool.core;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ermittelt die festen Arbeitsverzeichnisse des Tools und erkennt vorhandene Pakete.
 *
 * <pre>
 * Programmordner/
 *   Import/     Paket-ZIPs von Rechner 1
 *   Export/     erzeugte Paket-ZIPs
 *   Logfiles/
 * </pre>
 *
 * Das Programmverzeichnis ist der Ordner der laufenden Jar-Datei. Beim Start aus
 * einer IDE oder aus {@code target/classes} wird das aktuelle Arbeitsverzeichnis
 * verwendet. Für Tests kann es über {@code -Dsynchronizetool.baseDir=...} gesetzt werden.
 */
public final class ToolDirectories {

    public static final String IMPORT = "Import";
    public static final String EXPORT = "Export";
    public static final String LOGFILES = "Logfiles";

    private static final String BASE_OVERRIDE = "synchronizetool.baseDir";

    private ToolDirectories() {
    }

    /** Ordner, in dem die Jar-Datei liegt – sonst das aktuelle Arbeitsverzeichnis. */
    public static Path programDir() {
        String override = System.getProperty(BASE_OVERRIDE);
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        try {
            URL location = ToolDirectories.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null && "file".equalsIgnoreCase(location.getProtocol())) {
                Path path = Paths.get(location.toURI());
                if (Files.isRegularFile(path)) {
                    Path parent = path.getParent();
                    if (parent != null) {
                        return parent.toAbsolutePath().normalize();
                    }
                }
            }
        } catch (Exception ignored) {
            // Auf das Arbeitsverzeichnis zurückfallen.
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public static Path importDir() {
        return programDir().resolve(IMPORT);
    }

    public static Path exportDir() {
        return programDir().resolve(EXPORT);
    }

    public static Path logfilesDir() {
        return programDir().resolve(LOGFILES);
    }

    /** Legt Import, Export und Logfiles an, falls sie noch fehlen. */
    public static void ensureCreated() throws IOException {
        Files.createDirectories(importDir());
        Files.createDirectories(exportDir());
        Files.createDirectories(logfilesDir());
    }

    /**
     * Ein vollständiges Paket ist ein Ordner mit config.txt und payload/ oder eine
     * entsprechende ZIP-Datei. ZIP-Pakete sind die Regel; entpackte Ordner bleiben
     * aus Kompatibilitätsgründen lesbar.
     */
    public static boolean isCompletePackage(Path path) {
        if (path == null) {
            return false;
        }
        if (Files.isDirectory(path)) {
            return Files.isRegularFile(path.resolve(PackageService.CONFIG_FILE))
                    && Files.isDirectory(path.resolve(PackageService.PAYLOAD_DIR));
        }
        return PackageArchive.isCompleteArchive(path);
    }

    /** Liefert alle vollständigen Pakete (ZIP oder Ordner) direkt unterhalb von {@code parent}. */
    public static List<Path> listPackages(Path parent) {
        if (parent == null || !Files.isDirectory(parent)) {
            return List.of();
        }
        try (var stream = Files.list(parent)) {
            List<Path> packages = new ArrayList<>();
            stream.filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(ToolDirectories::isCompletePackage)
                    .forEach(packages::add);
            packages.sort(Comparator
                    .comparing((Path path) -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(path -> path.getFileName().toString()));
            return List.copyOf(packages);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Prüft, ob {@code child} direkt in {@code parent} liegt. */
    public static boolean isDirectChild(Path parent, Path child) {
        if (parent == null || child == null) {
            return false;
        }
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        return normalizedParent.equals(normalizedChild.getParent());
    }
}
