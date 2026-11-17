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

/** Lädt ein vom {@link PackageServer} bereitgestelltes ZIP und speichert oder entpackt es. */
public final class PackageDownloader {

    private final Consumer<String> log;

    public PackageDownloader(Consumer<String> log) {
        this.log = log;
    }

    /**
     * Lädt das Paket als ZIP-Datei nach {@code archiveFile}. Die vorhandene Datei wird
     * erst ersetzt, nachdem der Download vollständig und gültig ist.
     */
    public Path downloadArchive(String url, Path archiveFile) throws IOException {
        log.accept("Lade Paket von: " + url);
        Path target = archiveFile.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Ungültiger Zielpfad: " + archiveFile);
        }
        Files.createDirectories(parent);

        Path tempZip = Files.createTempFile(parent, ".synctool-download-", ".zip");
        try {
            fetch(url, tempZip);
            if (!PackageArchive.isCompleteArchive(tempZip)) {
                throw new IOException("Die geladene Datei ist kein vollständiges SynchronizeTool-Paket.");
            }
            Files.move(tempZip, target, StandardCopyOption.REPLACE_EXISTING);
            log.accept("Paket gespeichert: " + target);
            return target;
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    /** Lädt ein Paket und entpackt es direkt in {@code destination}. */
    public Path download(String url, Path destination) throws IOException {
        log.accept("Lade Paket von: " + url);
        Path tempZip = Files.createTempFile("synctool-download-", ".zip");
        try {
            fetch(url, tempZip);
            FileOps.deleteRecursively(destination);
            Files.createDirectories(destination);
            unzip(tempZip, destination);
        } finally {
            Files.deleteIfExists(tempZip);
        }
        log.accept("Paket heruntergeladen nach: " + destination);
        return destination;
    }

    private void fetch(String url, Path tempZip) throws IOException {
        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige URL: " + url, e);
        }
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(0); // große Pakete dürfen länger dauern
        connection.setRequestProperty("Accept", "application/zip");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " beim Laden von " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, tempZip, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            connection.disconnect();
        }
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
