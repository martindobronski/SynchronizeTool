package de.synchronizetool.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Stellt ein Paket als ZIP über HTTP im lokalen Netzwerk bereit. Der Zugriff ist über
 * einen zufälligen Token in der URL geschützt. Die Übertragung ist nicht verschlüsselt
 * und für vertrauenswürdige LANs gedacht.
 *
 * <p>Als Quelle dient entweder ein Paketordner, der beim Start gepackt wird, oder eine
 * bereits vorhandene Paket-ZIP, die unverändert ausgeliefert wird.</p>
 */
public final class PackageServer implements AutoCloseable {

    private final Consumer<String> log;
    private HttpServer server;
    private ExecutorService executor;
    private Path zipFile;
    private boolean deleteZipOnStop;
    private String token;
    private String url;

    public PackageServer(Consumer<String> log) {
        this.log = log;
    }

    /**
     * Startet den HTTP-Server für ein Paketverzeichnis oder eine vorhandene Paket-ZIP.
     * Port 0 = freier Port.
     */
    public synchronized String start(Path packageSource, int port) throws IOException {
        stop();
        Path source = packageSource.toAbsolutePath().normalize();
        try {
            if (Files.isDirectory(source)) {
                zipFile = Files.createTempFile("synctool-package-", ".zip");
                createZip(source, zipFile);
                deleteZipOnStop = true;
            } else if (PackageArchive.isCompleteArchive(source)) {
                zipFile = source;
                deleteZipOnStop = false;
                log.accept("Freigabe verwendet vorhandene Paket-ZIP: " + source);
            } else {
                throw new IOException("Paket nicht gefunden oder unvollständig: " + source);
            }

            token = UUID.randomUUID().toString().replace("-", "");
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/package.zip", this::handlePackage);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "synctool-http");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.start();

            int actualPort = server.getAddress().getPort();
            url = "http://" + localAddress() + ":" + actualPort + "/package.zip?token=" + token;
            log.accept("Paket freigegeben (" + readableSize(Files.size(zipFile)) + "): " + url);
            log.accept("Auf Rechner 2 im Reiter \"Netzwerk\" diese URL einfügen.");
            return url;
        } catch (IOException | RuntimeException e) {
            stop();
            throw e;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            log.accept("Paketfreigabe beendet.");
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (zipFile != null && deleteZipOnStop) {
            try {
                Files.deleteIfExists(zipFile);
            } catch (IOException ignored) {
                // temporäre Datei; beim nächsten Aufräumen durch das OS entfernt
            }
        }
        zipFile = null;
        deleteZipOnStop = false;
        token = null;
        url = null;
    }

    public synchronized String url() {
        return url;
    }

    public synchronized int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public synchronized String token() {
        return token;
    }

    @Override
    public void close() {
        stop();
    }

    // ------------------------------------------------------------------ HTTP

    private void handlePackage(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!hasValidToken(exchange.getRequestURI().getQuery())) {
                byte[] body = "Ungültiger oder fehlender Token.".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(403, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            if (zipFile == null || !Files.isRegularFile(zipFile)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"synctool-package.zip\"");
            exchange.sendResponseHeaders(200, Files.size(zipFile));
            try (OutputStream out = exchange.getResponseBody()) {
                Files.copy(zipFile, out);
            }
        } catch (Exception e) {
            log.accept("HTTP-Fehler: " + e.getMessage());
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException ignored) {
                // Antwort konnte nicht mehr gesendet werden
            }
        } finally {
            exchange.close();
        }
    }

    private boolean hasValidToken(String query) {
        if (query == null || token == null) {
            return false;
        }
        for (String part : query.split("&")) {
            if (part.equals("token=" + token)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ ZIP

    private static void createZip(Path packageDir, Path zipFile) throws IOException {
        Path root = packageDir.toAbsolutePath().normalize();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String relative = root.relativize(dir).toString().replace('\\', '/');
                    if (!relative.isEmpty()) {
                        out.putNextEntry(new ZipEntry(relative + "/"));
                        out.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
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

    // --------------------------------------------------------------- Netzwerk

    private static String localAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (java.net.InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress inet = address.getAddress();
                    if (inet instanceof Inet4Address && inet.isSiteLocalAddress()) {
                        return inet.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Fallback unten
        }
        return "127.0.0.1";
    }

    private static String readableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
