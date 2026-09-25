package me.piitex.cca.backend.model;

import me.piitex.cca.App;
import me.piitex.engine.io.download.Download;
import me.piitex.engine.io.download.DownloadListener;
import me.piitex.engine.io.download.DownloadManager;
import me.piitex.engine.io.download.DownloadState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Model downloads from Hugging Face into the models folder. Lives for the whole run rather than with the Models
 * screen, so a download keeps going while the user is somewhere else and the screen finds it again when reopened.
 * <p>
 * One file at a time, so the parts of a split model come down in order and don't fight over the connection.
 * Stopping keeps the .part file, and downloading the same model again continues from it.
 */
public final class ModelDownloads {
    private static final ModelDownloads INSTANCE = new ModelDownloads();

    private final DownloadManager manager = new DownloadManager(1);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ModelDownloads() {
    }

    public static ModelDownloads get() {
        return INSTANCE;
    }

    public DownloadManager manager() {
        return manager;
    }

    // Called from a download's thread whenever one changes state. Move to the render thread before touching the UI.
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public boolean isDownloaded(HuggingFace.Variant variant) {
        return variant.files().stream().allMatch(file -> Files.isRegularFile(target(file)));
    }

    public boolean isDownloading(HuggingFace.Variant variant) {
        return manager.getDownloads().stream().anyMatch(d -> !d.getState().isFinished()
                && variant.files().stream().anyMatch(file -> target(file).equals(d.getTarget())));
    }

    // Returns why it couldn't start, or null once it's queued. token is for gated repos, blank for none.
    public String start(HuggingFace.Variant variant, String token) {
        if (isDownloading(variant)) return null;
        Path directory = ModelLibrary.directory();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            App.logger.error("Could not create {}", directory, e);
            return "Couldn't create the models folder: " + e.getMessage();
        }

        List<HuggingFace.RemoteFile> missing = variant.files().stream().filter(file -> !Files.isRegularFile(target(file))).toList();
        if (missing.isEmpty()) return null;
        long needed = 0;
        for (HuggingFace.RemoteFile file : missing) {
            needed += Math.max(0, file.size() - partSize(target(file)));
        }
        try {
            long free = Files.getFileStore(directory).getUsableSpace();
            if (needed > free) {
                return "Not enough free space. This needs " + ModelLibrary.formatSize(needed) + " and the drive has " + ModelLibrary.formatSize(free) + " free.";
            }
        } catch (IOException e) {
            App.logger.warn("Could not check the free space in {}", directory, e); // Try anyway.
        }

        for (HuggingFace.RemoteFile file : missing) {
            Path target = target(file);
            // Replace an earlier cancelled or failed try, it would only sit in the list next to the new one.
            manager.getDownloads().stream().filter(d -> d.getTarget().equals(target)).forEach(manager::remove);

            Download download = new Download(file.url(), target);
            download.setDisplayName(file.fileName());
            if (file.size() > 0) download.setExpectedSize(file.size());
            download.setExpectedSha256(file.sha256());
            // Java drops this on the redirect to the CDN, so it only ever goes to huggingface.co.
            if (!token.isBlank()) download.setHeader("Authorization", "Bearer " + token);
            download.addListener(new DownloadListener() {
                @Override
                public void onStateChanged(Download d, DownloadState state) {
                    notifyListeners();
                }
            });
            manager.add(download);
        }
        App.logger.info("Downloading {} from {}", variant.title(), missing.getFirst().repo());
        notifyListeners();
        return null;
    }

    // Always the file's own name in the models folder, a split model's parts have to sit together.
    private static Path target(HuggingFace.RemoteFile file) {
        return ModelLibrary.directory().resolve(file.fileName());
    }

    private static long partSize(Path target) {
        try {
            Path part = target.resolveSibling(target.getFileName() + ".part");
            return Files.exists(part) ? Files.size(part) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
