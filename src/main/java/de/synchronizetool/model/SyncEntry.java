package de.synchronizetool.model;

import de.synchronizetool.core.PathMapper;

import java.nio.file.Path;
import java.util.Objects;

/** Ein ausgewähltes Element: entweder eine einzelne Datei oder ein ganzes Verzeichnis. */
public final class SyncEntry {

    public enum Kind {
        FILE, DIRECTORY
    }

    private final Kind kind;
    private final String logicalPath;

    private SyncEntry(Kind kind, String logicalPath) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.logicalPath = Objects.requireNonNull(logicalPath, "logicalPath");
    }

    public static SyncEntry file(Path absolute) {
        return new SyncEntry(Kind.FILE, PathMapper.toLogical(absolute));
    }

    public static SyncEntry directory(Path absolute) {
        return new SyncEntry(Kind.DIRECTORY, PathMapper.toLogical(absolute));
    }

    public static SyncEntry of(String token, String logicalPath) {
        Kind kind = switch (token) {
            case "F" -> Kind.FILE;
            case "D" -> Kind.DIRECTORY;
            default -> throw new IllegalArgumentException("Unbekannter Eintragstyp: " + token);
        };
        return new SyncEntry(kind, logicalPath);
    }

    public Kind kind() {
        return kind;
    }

    public String logicalPath() {
        return logicalPath;
    }

    public String token() {
        return kind == Kind.FILE ? "F" : "D";
    }

    /** Pfad auf dem gerade aktiven Rechner (Quelle oder Ziel). */
    public Path localPath() {
        return PathMapper.resolve(logicalPath);
    }

    /** Position der Daten im Paketverzeichnis. */
    public String payloadRelative() {
        return PathMapper.toPayloadRelative(logicalPath);
    }

    /** True, wenn dieser Eintrag innerhalb des Verzeichnisses {@code other} liegt. */
    public boolean isCoveredBy(SyncEntry other) {
        if (other.kind != Kind.DIRECTORY || this == other) {
            return false;
        }
        String base = trimTrailingSlash(other.logicalPath);
        String path = trimTrailingSlash(this.logicalPath);
        if (base.equals(path)) {
            return true;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String b = windows ? base.toLowerCase() : base;
        String p = windows ? path.toLowerCase() : path;
        return p.startsWith(b.endsWith("/") ? b : b + "/");
    }

    private static String trimTrailingSlash(String value) {
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SyncEntry other)) {
            return false;
        }
        return kind == other.kind && logicalPath.equals(other.logicalPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, logicalPath);
    }

    @Override
    public String toString() {
        return (kind == Kind.FILE ? "[Datei]  " : "[Verz.]  ") + logicalPath;
    }
}
