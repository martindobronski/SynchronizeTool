package de.synchronizetool.core;

import de.synchronizetool.model.SyncConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Liest und schreibt SynchronizeTool-Pakete als ZIP-Dateien.
 *
 * <p>Ein ZIP-Paket enthält im Wurzelverzeichnis die {@code config.txt} und den Ordner
 * {@code payload/}. Beim Einspielen wird es in einen temporären Ordner entpackt; die
 * ZIP-Datei bleibt die transportable Ablage.</p>
 */
public final class PackageArchive {

    public static final String EXTENSION = ".zip";
    private static final String CONFIG_ENTRY = PackageService.CONFIG_FILE;
    private static final String PAYLOAD_ENTRY = PackageService.PAYLOAD_DIR;

    private PackageArchive() {
    }

    /** True, wenn der Pfad eine vorhandene Datei mit der Endung {@code .zip} ist. */
    public static boolean isArchive(Path path) {
        return path != null
                && Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    /** True, wenn die ZIP-Datei eine config.txt und einen payload-Ordner enthält. */
    public static boolean isCompleteArchive(Path archive) {
        if (!isArchive(archive)) {
            return false;
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            return hasEntry(zip, CONFIG_ENTRY) && hasPayload(zip);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasEntry(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        return entry != null && !entry.isDirectory();
    }

    private static boolean hasPayload(ZipFile zip) {
        return zip.stream().anyMatch(entry -> {
            String name = entry.getName().replace('\\', '/');
            return name.equals(PAYLOAD_ENTRY + "/") || name.startsWith(PAYLOAD_ENTRY + "/");
        });
    }

    /** Liefert alle vollständigen ZIP-Pakete direkt unterhalb von {@code parent}. */
    public static List<Path> listArchives(Path parent) {
        if (parent == null || !Files.isDirectory(parent)) {
            return List.of();
        }
        try (var stream = Files.list(parent)) {
            List<Path> archives = new ArrayList<>();
            stream.filter(PackageArchive::isCompleteArchive).forEach(archives::add);
            archives.sort(Comparator
                    .comparing((Path path) -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(path -> path.getFileName().toString()));
            return List.copyOf(archives);
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Packt ein Paketverzeichnis als ZIP. Die Zieldatei wird erst nach erfolgreichem
     * Schreiben ersetzt, damit kein halbes Paket zurückbleibt.
     */
    public static void create(Path packageDir, Path archiveFile) throws IOException {
        if (!Files.isDirectory(packageDir)) {
            throw new IOException("Paketverzeichnis nicht gefunden: " + packageDir);
        }
        Path target = archiveFile.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Ungültiger Zielpfad: " + archiveFile);
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".synctool-", EXTENSION + ".tmp");
        try {
            zip(packageDir, temp);
            moveReplacing(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void zip(Path packageDir, Path zipFile) throws IOException {
        Path root = packageDir.toAbsolutePath().normalize();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    String relative = root.relativize(dir).toString().replace('\\', '/');
                    if (!relative.isEmpty()) {
                        out.putNextEntry(new ZipEntry(relative + "/"));
                        out.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    ZipEntry entry = new ZipEntry(relative);
                    entry.setLastModifiedTime(Files.getLastModifiedTime(file));
                    out.putNextEntry(entry);
                    Files.copy(file, out);
                    out.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Entpackt ein ZIP-Paket nach {@code destination}. Einträge außerhalb des Ziels
     * werden abgelehnt, damit ein manipuliertes Paket keine fremden Dateien überschreibt.
     */
    public static void extract(Path archive, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.isEmpty()) {
                    in.closeEntry();
                    continue;
                }
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Ungültiger Eintrag im Paket: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                in.closeEntry();
            }
        }
    }

    /** Entpackt ein ZIP in einen neuen temporären Ordner; der Aufrufer räumt ihn auf. */
    public static Path extractToTemp(Path archive) throws IOException {
        Path temp = Files.createTempDirectory("synctool-entpackt-");
        try {
            extract(archive, temp);
            return temp;
        } catch (IOException | RuntimeException e) {
            FileOps.deleteRecursively(temp);
            throw e;
        }
    }

    /** Liest die config.txt direkt aus dem ZIP, ohne das gesamte Paket zu entpacken. */
    public static SyncConfig readConfig(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(CONFIG_ENTRY);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("Keine " + CONFIG_ENTRY + " im Paket: " + archive);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return SyncConfig.load(in, archive + "!/" + CONFIG_ENTRY);
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
