package de.synchronizetool.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Einfache Ausschlussmuster im Stil von .gitignore.
 *
 * <p>Unterstützt {@code *} (innerhalb eines Pfadsegments), {@code ?} und {@code **}
 * (über Verzeichnisgrenzen hinweg). Muster ohne {@code /} werden auf jedes Pfadsegment
 * angewendet, z. B. trifft {@code node_modules} auch {@code a/node_modules/x.js}.
 * Groß-/Kleinschreibung wird nicht unterschieden.</p>
 */
public final class ExcludeMatcher {

    private record Rule(Pattern regex, boolean anySegment) {
    }

    private final List<Rule> rules = new ArrayList<>();

    public ExcludeMatcher(List<String> patterns) {
        for (String raw : patterns) {
            String pattern = raw == null ? "" : raw.trim().replace('\\', '/');
            if (pattern.isEmpty() || pattern.startsWith("#")) {
                continue;
            }
            while (pattern.startsWith("./")) {
                pattern = pattern.substring(2);
            }
            rules.add(new Rule(Pattern.compile(globToRegex(pattern), Pattern.CASE_INSENSITIVE),
                    !pattern.contains("/")));
        }
    }

    public static ExcludeMatcher empty() {
        return new ExcludeMatcher(List.of());
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public boolean matches(Path relative) {
        String normalized = relative.toString().replace('\\', '/');
        for (Rule rule : rules) {
            if (rule.anySegment) {
                for (Path segment : relative) {
                    if (rule.regex.matcher(segment.toString()).matches()) {
                        return true;
                    }
                }
                if (relative.getNameCount() == 0 && rule.regex.matcher(normalized).matches()) {
                    return true;
                }
            } else if (rule.regex.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            out.append("(?:.*/)?");
                            i++;
                        } else {
                            out.append(".*");
                        }
                    } else {
                        out.append("[^/]*");
                    }
                }
                case '?' -> out.append("[^/]");
                case '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' ->
                        out.append('\\').append(c);
                default -> out.append(c);
            }
        }
        return out.append('$').toString();
    }
}
