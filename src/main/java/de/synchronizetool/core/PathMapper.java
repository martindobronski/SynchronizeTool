package de.synchronizetool.core;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Übersetzt zwischen three Pfadwelten:
 *
 * <ul>
 *   <li><b>lokaler Pfad</b>: absoluter Pfad auf dem jeweiligen Rechner</li>
 *   <li><b>logischer Pfad</b>: in der config.txt gespeicherter Pfad; das Home-Verzeichnis
 *       wird dabei durch {@code ${HOME}} ersetzt, damit er auf beiden Rechnern passt</li>
 *   <li><b>Payload-Pfad</b>: relative Position innerhalb des Paketverzeichnisses</li>
 * </ul>
 */
public final class PathMapper {

    public static final String HOME_TOKEN = "${HOME}";

    private PathMapper() {
    }

    /** Absoluten Pfad des aktuellen Rechners in den logischen Pfad umwandeln. */
    public static String toLogical(Path absolute) {
        Path path = absolute.toAbsolutePath().normalize();
        Path home = home();
        if (path.startsWith(home)) {
            Path relative = home.relativize(path);
            String text = relative.toString().isEmpty() ? "" : relative.toString().replace(File.separatorChar, '/');
            return text.isEmpty() ? HOME_TOKEN : HOME_TOKEN + "/" + text;
        }
        return path.toString().replace(File.separatorChar, '/');
    }

    /** Logischen Pfad aus der config.txt auf dem aktuellen Rechner auflösen. */
    public static Path resolve(String logical) {
        String value = logical.replace('\\', '/');
        if (value.equals(HOME_TOKEN) || value.startsWith(HOME_TOKEN + "/")) {
            String rest = value.substring(HOME_TOKEN.length());
            while (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            Path home = home();
            return rest.isEmpty() ? home : home.resolve(rest.replace('/', File.separatorChar)).normalize();
        }
        return Paths.get(value.replace('/', File.separatorChar)).toAbsolutePath().normalize();
    }

    /** Logischen Pfad in einen sicheren, relativen Pfad innerhalb des Pakets umwandeln. */
    public static String toPayloadRelative(String logical) {
        String value = logical.replace('\\', '/');
        if (value.equals(HOME_TOKEN) || value.startsWith(HOME_TOKEN + "/")) {
            value = "HOME" + value.substring(HOME_TOKEN.length());
        }
        value = value.replace(":", "");
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Leerer Payload-Pfad für: " + logical);
        }
        return value;
    }

    private static Path home() {
        return Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
    }
}
