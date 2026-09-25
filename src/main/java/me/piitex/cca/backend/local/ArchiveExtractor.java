package me.piitex.cca.backend.local;

import me.piitex.engine.utils.Platform;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Extracts a llama.cpp release (.zip or .tar.gz) into one flat folder.
 * <p>
 * The archives don't have a fixed layout, some have a versioned folder and some split into bin/ and lib/.
 * The executables look for their libraries in their own folder (@executable_path), so everything has to
 * end up next to each other or llama-server can't find its .dylib/.so files.
 */
public final class ArchiveExtractor {
    private ArchiveExtractor() {
    }

    /**
     * @param markerFileName the executable to look for once everything is extracted
     * @return where the executable ended up, or null if the archive didn't have it
     */
    public static Path extract(Path archive, Path destination, String markerFileName) throws IOException {
        Files.createDirectories(destination);
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);

        if (name.endsWith(".zip")) {
            extractZip(archive, destination);
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archive, destination);
        } else {
            throw new IOException("Unsupported archive format: " + archive.getFileName());
        }

        flattenAllFiles(destination);
        markExecutablesRunnable(destination);

        Path marker = destination.resolve(markerFileName);
        return Files.exists(marker) ? marker : null;
    }

    private static void extractZip(Path archive, Path destination) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                Path target = safeResolve(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                if (entry.isUnixSymlink()) {
                    createSymlink(target, zip.getUnixSymlink(entry));
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void extractTarGz(Path archive, Path destination) throws IOException {
        try (InputStream fileIn = Files.newInputStream(archive);
             GzipCompressorInputStream gzIn = new GzipCompressorInputStream(fileIn);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzIn)) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                Path target = safeResolve(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                if (entry.isSymbolicLink()) {
                    createSymlink(target, entry.getLinkName());
                    continue;
                }
                Files.copy(tarIn, target, StandardCopyOption.REPLACE_EXISTING);
                if ((entry.getMode() & 0111) != 0) {
                    target.toFile().setExecutable(true);
                }
            }
        }
    }

    // The symlinks matter. The binaries load libraries by the short name (libllama-common.0.dylib) and
    // only the symlink has that name, the real file is versioned. Skipping them gives "Library not loaded".
    private static void createSymlink(Path target, String linkTarget) throws IOException {
        Files.deleteIfExists(target);
        Files.createSymbolicLink(target, Path.of(linkTarget));
    }

    // Blocks entries like ../../ from writing outside the destination (zip slip).
    private static Path safeResolve(Path destination, String entryName) throws IOException {
        Path target = destination.resolve(entryName).normalize();
        if (!target.startsWith(destination)) {
            throw new IOException("Archive entry escapes its destination: " + entryName);
        }
        return target;
    }

    private static void flattenAllFiles(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            if (file.getParent().equals(root)) continue;
            Files.move(file, root.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        deleteEmptyDirectories(root);
    }

    // Deepest first so the leftover wrapper folders are removed. Never removes root itself.
    private static void deleteEmptyDirectories(Path root) throws IOException {
        List<Path> directories;
        try (Stream<Path> stream = Files.walk(root)) {
            directories = stream.filter(p -> !p.equals(root) && Files.isDirectory(p))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
        }
        for (Path dir : directories) {
            try (Stream<Path> stream = Files.list(dir)) {
                if (stream.findAny().isEmpty()) Files.delete(dir);
            }
        }
    }

    private static void markExecutablesRunnable(Path root) throws IOException {
        if (Platform.getCurrent() == Platform.WINDOWS) return;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path file : stream.toList()) {
                if (Files.isRegularFile(file) && file.getFileName().toString().startsWith("llama-")) {
                    file.toFile().setExecutable(true);
                }
            }
        }
    }
}
