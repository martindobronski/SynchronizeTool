package de.synchronizetool.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Lädt ein vom {@link PackageServer} bereitgestelltes ZIP und entpackt es. */
public final class PackageDownloader {

    private final Consumer<String> log;

    public PackageDownloader(Consumer<String> log) {
        this.log = log;
    }

    public Path download(String url, Path destination) throws IOException {
        log.accept("Lade Paket von: " + url);
        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige URL: " + url, e);
        }
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(0); // große Pakete dürfen länger dauern
        connection.setRequestProperty("Accept", "application/zip");

        Path tempZip = Files.createTempFile("synctool-download-", ".zip");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " beim Laden von " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, tempZip, StandardCopyOption.REPLACE_EXISTING);
            }
            FileOps.deleteRecursively(destination);
            Files.createDirectories(destination);
            unzip(tempZip, destination);
        } finally {
            connection.disconnect();
            Files.deleteIfExists(tempZip);
        }
        log.accept("Paket heruntergeladen nach: " + destination);
        return destination;
    }

    private void unzip(Path zip, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
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
}
