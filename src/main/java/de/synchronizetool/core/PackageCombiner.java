package de.synchronizetool.core;

import de.synchronizetool.model.SyncConfig;
import de.synchronizetool.model.SyncEntry;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Führt mehrere vorhandene Pakete zu einem Gesamtpaket zusammen.
 *
 * <p>Die Pakete werden in der übergebenen Reihenfolge priorisiert: Das erste Paket hat
 * Vorrang, wenn Dateien in mehreren Paketen vorkommen. Ausschlussmuster werden vereinigt.
 * Die Payloads werden aus den vorhandenen Paketen übernommen – die ursprünglichen
 * Quelldateien müssen also nicht mehr vorhanden sein.</p>
 */
public final class PackageCombiner {

    private static final int MAX_CONFLICT_LINES = 20;
    private static final StandardCopyOption[] COPY_OPTIONS = {
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES
    };

    private final Consumer<String> log;
    private boolean attributeWarningLogged;

    public PackageCombiner(Consumer<String> log) {
        this.log = log;
    }

    private record Source(Path configFile, Path packageDir, Path payload, SyncConfig config) {
    }

    private record EntryRef(Source source, SyncEntry entry) {
    }

    /**
     * Kombiniert die Pakete der angegebenen {@code config.txt}-Dateien in ein neues
     * Paketverzeichnis. {@code configFiles} ist von höchster zu niedrigster Priorität
     * sortiert.
     *
     * @return die Konfiguration des neuen Gesamtpakets
     */
    public SyncConfig combine(List<Path> configFiles, Path targetPackage) throws IOException {
        if (configFiles == null || configFiles.isEmpty()) {
            throw new IOException("Es wurde kein Paket ausgewählt.");
        }
        Path target = targetPackage.toAbsolutePath().normalize();

        List<Source> sources = new ArrayList<>();
        for (Path configFile : configFiles) {
            Path file = configFile.toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                throw new IOException("Konfiguration nicht gefunden: " + file);
            }
            Path packageDir = file.getParent();
            if (packageDir == null) {
                throw new IOException("Konfiguration ohne Paketverzeichnis: " + file);
            }
            Path payload = packageDir.resolve(PackageService.PAYLOAD_DIR).toAbsolutePath().normalize();
            if (!Files.isDirectory(payload)) {
                throw new IOException("Kein " + PackageService.PAYLOAD_DIR
                        + "-Verzeichnis im Paket: " + packageDir);
            }
            SyncConfig config = SyncConfig.load(file);
            sources.add(new Source(file, packageDir, payload, config));
            log.accept("Kombiniere: " + packageDir + " (" + config.entries().size()
                    + " Einträge, " + config.excludes().size() + " Ausschlüsse)");
        }

        validateTarget(target, sources);
        validateNoStructuralConflicts(sources);

        SyncConfig combined = new SyncConfig();
        List<String> excludes = new ArrayList<>();
        for (Source source : sources) {
            for (SyncEntry entry : source.config().entries()) {
                combined.add(SyncEntry.of(entry.token(), normalize(entry.logicalPath())));
            }
            excludes.addAll(source.config().excludes());
        }
        combined.setExcludes(excludes);

        if (!combined.excludes().isEmpty()) {
            log.accept("Zusammengeführte Ausschlüsse (" + combined.excludes().size() + "): "
                    + combined.excludes());
        }

        Path payload = target.resolve(PackageService.PAYLOAD_DIR);
        Files.createDirectories(payload);
        Path normalizedPayload = payload.toAbsolutePath().normalize();
        ExcludeMatcher matcher = new ExcludeMatcher(combined.excludes());
        List<String> conflicts = new ArrayList<>();

        for (SyncEntry entry : combined.entries()) {
            mergeEntry(sources, entry, normalizedPayload, matcher, conflicts);
        }

        Path configFile = target.resolve(PackageService.CONFIG_FILE);
        combined.save(configFile);

        log.accept("Gesamtpaket: " + combined.entries().size() + " Einträge, "
                + combined.excludes().size() + " Ausschlüsse.");
        if (conflicts.isEmpty()) {
            log.accept("Keine inhaltlichen Überschneidungen zwischen den Paketen.");
        } else {
            log.accept("Überschreibungen durch höhere Priorität: " + conflicts.size());
            int shown = 0;
            for (String conflict : conflicts) {
                if (shown >= MAX_CONFLICT_LINES) {
                    log.accept("  ... und " + (conflicts.size() - MAX_CONFLICT_LINES) + " weitere");
                    break;
                }
                log.accept("  Konflikt: " + conflict);
                shown++;
            }
        }
        log.accept("Konfiguration gespeichert: " + configFile);
        log.accept("Gesamtpaket fertig: " + target);
        return combined;
    }

    private void validateTarget(Path target, List<Source> sources) throws IOException {
        for (Source source : sources) {
            if (target.equals(source.packageDir())
                    || target.startsWith(source.packageDir())
                    || source.packageDir().startsWith(target)) {
                throw new IOException("Das Zielpaket darf nicht im Quellpaket liegen oder es enthalten: "
                        + target);
            }
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(target)) {
            throw new IOException("Das Ziel ist kein Verzeichnis: " + target);
        }
        try (var children = Files.list(target)) {
            if (children.findAny().isPresent()) {
                throw new IOException("Das Zielverzeichnis ist nicht leer: " + target);
            }
        }
    }

    /**
     * Prüft, ob sich die Einträge widersprechen: Eine Datei kann nicht gleichzeitig
     * Verzeichnis sein oder Kinder enthalten.
     */
    private void validateNoStructuralConflicts(List<Source> sources) throws IOException {
        List<EntryRef> refs = new ArrayList<>();
        for (Source source : sources) {
            for (SyncEntry entry : source.config().entries()) {
                refs.add(new EntryRef(source, entry));
            }
        }

        Set<String> problems = new LinkedHashSet<>();
        for (int i = 0; i < refs.size(); i++) {
            EntryRef first = refs.get(i);
            for (int j = i + 1; j < refs.size(); j++) {
                EntryRef second = refs.get(j);
                String a = normalize(first.entry().logicalPath());
                String b = normalize(second.entry().logicalPath());
                boolean samePath = pathEquals(a, b);

                if (samePath && first.entry().kind() != second.entry().kind()) {
                    problems.add(describe(first) + " ist einmal Datei und einmal Verzeichnis");
                } else if (first.entry().kind() == SyncEntry.Kind.FILE && isDescendant(b, a)) {
                    problems.add(describe(first) + " ist eine Datei, enthält aber "
                            + second.entry().logicalPath());
                } else if (second.entry().kind() == SyncEntry.Kind.FILE && isDescendant(a, b)) {
                    problems.add(describe(second) + " ist eine Datei, enthält aber "
                            + first.entry().logicalPath());
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IOException("Die Pakete können nicht kombiniert werden:\n- "
                    + String.join("\n- ", problems));
        }
    }

    private static String describe(EntryRef ref) {
        return ref.entry().logicalPath() + " (" + shortName(ref.source().packageDir()) + ")";
    }

    private void mergeEntry(List<Source> sources, SyncEntry entry, Path payloadRoot,
            ExcludeMatcher matcher, List<String> conflicts) throws IOException {
        Path destination = safeResolve(payloadRoot, entry.payloadRelative());
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        boolean anyData = false;
        // Von niedrigster zu höchster Priorität kopieren, damit das oberste Paket gewinnt.
        for (int i = sources.size() - 1; i >= 0; i--) {
            Source source = sources.get(i);
            Path sourcePath = safeResolve(source.payload(), entry.payloadRelative());
            if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (entry.kind() == SyncEntry.Kind.FILE) {
                if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                    log.accept("Warnung: " + sourcePath + " ist keine Datei – übersprungen.");
                    continue;
                }
                anyData = true;
                copyEntryFile(sourcePath, destination, entry, source, conflicts);
            } else {
                if (!Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                    log.accept("Warnung: " + sourcePath + " ist kein Verzeichnis – übersprungen.");
                    continue;
                }
                anyData = true;
                Files.createDirectories(destination);
                overlayDirectory(sourcePath, destination, matcher, entry, source, conflicts);
            }
        }

        if (anyData) {
            log.accept("Kombiniert: " + entry);
        } else {
            log.accept("Warnung: kein Payload für " + entry.logicalPath()
                    + " in den gewählten Paketen gefunden.");
        }
    }

    private void copyEntryFile(Path source, Path destination, SyncEntry entry, Source packageSource,
            List<String> conflicts) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                    && Files.mismatch(destination, source) == -1) {
                return;
            }
            conflicts.add(entry.logicalPath() + " (Paket " + shortName(packageSource.packageDir())
                    + " hat Vorrang)");
            FileOps.deleteRecursively(destination);
        }
        copyFile(source, destination);
    }

    private void overlayDirectory(Path sourceRoot, Path destinationRoot, ExcludeMatcher matcher,
            SyncEntry entry, Source packageSource, List<String> conflicts) throws IOException {
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Path relative = sourceRoot.relativize(dir);
                if (!relative.toString().isEmpty() && matcher.matches(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path destination = destinationRoot.resolve(relative.toString());
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                    conflicts.add(displayPath(entry, relative) + " (Verzeichnis aus Paket "
                            + shortName(packageSource.packageDir()) + " hat Vorrang)");
                    FileOps.deleteRecursively(destination);
                }
                Files.createDirectories(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path relative = sourceRoot.relativize(file);
                if (matcher.matches(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.isSymbolicLink()) {
                    log.accept("Hinweis: symbolischer Link im Paket übersprungen: " + file);
                    return FileVisitResult.CONTINUE;
                }
                Path destination = destinationRoot.resolve(relative.toString());
                Path parent = destination.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                        conflicts.add(displayPath(entry, relative) + " (Datei aus Paket "
                                + shortName(packageSource.packageDir()) + " hat Vorrang)");
                        FileOps.deleteRecursively(destination);
                    } else if (Files.mismatch(destination, file) == -1) {
                        return FileVisitResult.CONTINUE;
                    } else {
                        conflicts.add(displayPath(entry, relative) + " (Paket "
                                + shortName(packageSource.packageDir()) + " hat Vorrang)");
                    }
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

    private Path safeResolve(Path root, String relative) throws IOException {
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root)) {
            throw new IOException("Pfad außerhalb des Pakets: " + relative);
        }
        return result;
    }

    private void copyFile(Path source, Path destination) throws IOException {
        try {
            Files.copy(source, destination, COPY_OPTIONS);
        } catch (IOException e) {
            if (!attributeWarningLogged) {
                attributeWarningLogged = true;
                log.accept("Hinweis: Datei-Attribute können hier nicht übernommen werden, kopiere nur Inhalte.");
            }
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String displayPath(SyncEntry entry, Path relative) {
        if (relative.toString().isEmpty()) {
            return entry.logicalPath();
        }
        return entry.logicalPath() + "/" + relative.toString().replace('\\', '/');
    }

    private static String shortName(Path packageDir) {
        Path name = packageDir.getFileName();
        return name == null ? packageDir.toString() : name.toString();
    }

    private static String normalize(String logicalPath) {
        String value = logicalPath.replace('\\', '/');
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean pathEquals(String a, String b) {
        return windows() ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    private static boolean isDescendant(String child, String ancestor) {
        String c = windows() ? child.toLowerCase() : child;
        String a = windows() ? ancestor.toLowerCase() : ancestor;
        return c.startsWith(a.endsWith("/") ? a : a + "/");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
