package de.synchronizetool.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Kleine Dateisystem-Helfer, die an mehreren Stellen gebraucht werden. */
public final class FileOps {

    private FileOps() {
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> paths;
            try (var stream = Files.walk(path)) {
                paths = stream.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    /**
     * Erzeugt einen freien Namen im selben Verzeichnis: {@code name_suffix},
     * bei Kollision {@code name_suffix_2}, {@code name_suffix_3}, ...
     */
    public static Path uniqueSibling(Path original, String suffix) {
        Path parent = original.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Pfad ohne Elternverzeichnis: " + original);
        }
        String name = original.getFileName().toString();
        Path candidate = parent.resolve(name + "_" + suffix);
        int counter = 2;
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = parent.resolve(name + "_" + suffix + "_" + counter++);
        }
        return candidate;
    }
}
