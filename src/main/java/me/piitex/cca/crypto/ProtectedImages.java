package me.piitex.cca.crypto;

import me.piitex.cca.App;
import me.piitex.engine.ui.overlays.ImageOverlay;
import org.jetbrains.skia.Image;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

// The icons of characters, users and templates. On disk they're encrypted like everything else the user made, so
// nothing can be looked at from the file system. Decoding happens here, the engine only ever sees the decoded picture.
// A file without the header is read as a plain image, that's a picture just picked from the user's own folders.
public final class ProtectedImages {
    private static final byte[] MAGIC = "CCAI1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_CACHED = 64;

    private record Cached(long modified, long size, Image image) {
    }

    // Least recently used goes first. Nothing is closed on the way out, an overlay may still be drawing the picture.
    private static final Map<Path, Cached> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Cached> eldest) {
            return size() > MAX_CACHED;
        }
    };

    // Pictures being read right now, by file.
    private static final Map<Path, CompletableFuture<Image>> LOADING = new ConcurrentHashMap<>();

    // A few at a time, each one holds the file in memory several times over while it's decrypted.
    private static final Executor PRELOADER = Executors.newFixedThreadPool(Math.clamp(Runtime.getRuntime().availableProcessors() - 1, 1, 4), task -> {
        Thread thread = new Thread(task, "icon-preload");
        thread.setDaemon(true);
        return thread;
    });

    private ProtectedImages() {
    }

    public static boolean isProtected(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            byte[] head = new byte[MAGIC.length];
            return in.read(head) == MAGIC.length && Arrays.equals(head, MAGIC);
        }
    }

    // Reads source (plain or encrypted) and writes it to dest encrypted. Source and dest can be the same file.
    public static void write(FileCrypter crypter, Path dest, Path source) throws IOException {
        if (isSame(dest, source) && isProtected(source)) return;
        byte[] encrypted = crypter.encrypt(read(crypter, source));
        byte[] contents = new byte[MAGIC.length + encrypted.length];
        System.arraycopy(MAGIC, 0, contents, 0, MAGIC.length);
        System.arraycopy(encrypted, 0, contents, MAGIC.length, encrypted.length);

        Files.createDirectories(dest.toAbsolutePath().getParent());
        Path temp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Files.write(temp, contents);
        try {
            Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        synchronized (CACHE) {
            CACHE.remove(dest.toAbsolutePath().normalize());
        }
    }

    // The image file's bytes, decrypted if they were encrypted.
    public static byte[] read(Path file) throws IOException {
        return read(FileCrypter.forEnvironment(App.environment), file);
    }

    private static byte[] read(FileCrypter crypter, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length <= MAGIC.length || !Arrays.equals(bytes, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) return bytes;
        return crypter.decrypt(Arrays.copyOfRange(bytes, MAGIC.length, bytes.length));
    }

    // One decoded picture per file, since the hubs draw every card again on each render. Null if it can't be read.
    // Decrypting a big picture takes a while, so this is best kept off the render thread, see preload.
    public static Image load(Path file) {
        Path key = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(key)) return null;
        try {
            long modified = Files.getLastModifiedTime(key).toMillis();
            long size = Files.size(key);
            synchronized (CACHE) {
                Cached cached = CACHE.get(key);
                if (cached != null && cached.modified() == modified && cached.size() == size) return cached.image();
            }
            // Whoever asks first reads the file, anyone else asking for it meanwhile waits for that instead of reading it again.
            CompletableFuture<Image> mine = new CompletableFuture<>();
            CompletableFuture<Image> running = LOADING.putIfAbsent(key, mine);
            if (running != null) return running.join();
            Image image = null;
            try {
                image = Image.Companion.makeFromEncoded(read(key));
                if (image != null) {
                    synchronized (CACHE) {
                        CACHE.put(key, new Cached(modified, size, image));
                    }
                }
                return image;
            } finally {
                mine.complete(image);
                LOADING.remove(key, mine);
            }
        } catch (IOException | RuntimeException e) {
            App.logger.warn("Could not read an icon", e);
            return null;
        }
    }

    // Reads the pictures in the background so the first draw of a hub or a chat finds them ready.
    public static void preload(Collection<Path> files) {
        for (Path file : files) {
            PRELOADER.execute(() -> load(file));
        }
    }

    // The picture sized to the box, or null if the file isn't a readable image.
    public static ImageOverlay overlay(Path file, double width, double height) {
        Image image = load(file);
        return image == null ? null : ImageOverlay.of(image, width, height);
    }

    private static boolean isSame(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }
}
