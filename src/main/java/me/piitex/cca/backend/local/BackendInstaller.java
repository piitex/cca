package me.piitex.cca.backend.local;

import me.piitex.cca.App;
import me.piitex.cca.backend.local.BackendReleases.Offer;
import me.piitex.cca.backend.local.GitHubReleaseFinder.ReleaseAsset;
import me.piitex.cca.backend.model.HardwareProbe;
import me.piitex.cca.backend.server.ServerProcess;
import me.piitex.engine.io.download.Download;
import me.piitex.engine.io.download.DownloadListener;
import me.piitex.engine.io.download.DownloadManager;
import me.piitex.engine.io.download.DownloadState;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Downloads a llama.cpp backend and swaps it in for the one in use. Lives for the whole run, like ModelDownloads,
 * so an install keeps going while the user is somewhere else.
 * <p>
 * Nothing touches the working backend until the new one is downloaded, unpacked and has answered
 * "llama-server --version". Then the folders are swapped, so a bad download or a build that won't run on
 * this computer leaves the old backend alone.
 */
public final class BackendInstaller {
    private static final BackendInstaller INSTANCE = new BackendInstaller();

    public enum Stage {IDLE, DOWNLOADING, INSTALLING, DONE, FAILED}

    private final DownloadManager manager = new DownloadManager(1);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private volatile Stage stage = Stage.IDLE;
    private volatile boolean cleaning;
    // This install's downloads, so cleanup can wait until they've closed their files.
    private volatile List<Download> downloads = List.of();
    private volatile Offer offer;
    private volatile String message = "";
    // Where the install is once it's downloaded: what it's doing now and how many of its steps are done, 0 to 1.
    private volatile String step = "";
    private volatile double stepProgress;
    // Only checked from the download threads, a second failure or cancel of the same install is ignored.
    private final Object lock = new Object();

    private BackendInstaller() {
    }

    public static BackendInstaller get() {
        return INSTANCE;
    }

    public DownloadManager manager() {
        return manager;
    }

    public Stage getStage() {
        return stage;
    }

    // Includes clearing away a download that was stopped, a new install would have its files deleted.
    public boolean isBusy() {
        return stage == Stage.DOWNLOADING || stage == Stage.INSTALLING || cleaning;
    }

    // What's being installed, or was the last time. Null before the first.
    public Offer getOffer() {
        return offer;
    }

    // Why it failed, or a warning about a successful install. Empty otherwise.
    public String getMessage() {
        return message;
    }

    public String getStep() {
        return step;
    }

    public double getStepProgress() {
        return stepProgress;
    }

    // Called from whatever thread changed something. Move to the render thread before touching the UI.
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    // Clears the result of a finished install so it's not shown again on the next visit.
    public void acknowledge() {
        synchronized (lock) {
            if (stage != Stage.DONE && stage != Stage.FAILED) return;
            stage = Stage.IDLE;
            message = "";
        }
        manager.clearFinished();
        notifyListeners();
    }

    // Stops a download in progress. The old backend was never touched.
    public void cancel() {
        if (stage == Stage.DOWNLOADING) manager.cancelAll();
    }

    // Returns why it couldn't start, or null once it's going.
    public String start(Offer chosen) {
        Path work = App.environment.getDataPath("backend-install");
        synchronized (lock) {
            if (isBusy()) return "Something is already being installed.";
            try {
                deleteTree(work);
                Files.createDirectories(work);
            } catch (IOException e) {
                App.logger.error("Could not prepare {}", work, e);
                return "Couldn't create a folder to download into: " + e.getMessage();
            }

            // Unpacked it takes a few times the download's size, and both are on the disk until the swap is done.
            try {
                long free = Files.getFileStore(work).getUsableSpace();
                long needed = chosen.size() * 3;
                if (needed > free) {
                    return "Not enough free space. This needs about " + format(needed) + " and the drive has " + format(free) + " free.";
                }
            } catch (IOException e) {
                App.logger.warn("Could not check the free space in {}", work, e); // Try anyway.
            }

            manager.clearFinished();
            offer = chosen;
            message = "";
            stage = Stage.DOWNLOADING;
        }
        App.logger.info("Installing backend '{}' ({})", chosen.variant().id(), chosen.tag());

        List<ReleaseAsset> files = new ArrayList<>();
        files.add(chosen.asset());
        if (chosen.runtime() != null) files.add(chosen.runtime());

        AtomicInteger waiting = new AtomicInteger(files.size());
        DownloadListener listener = new DownloadListener() {
            @Override
            public void onStateChanged(Download download, DownloadState state) {
                if (state == DownloadState.CANCELLED) cancelled(work);
                notifyListeners();
            }

            @Override
            public void onComplete(Download download) {
                if (waiting.decrementAndGet() == 0) Thread.ofVirtual().name("backend-install").start(() -> install(chosen, work));
            }

            @Override
            public void onFailed(Download download, Throwable error) {
                fail(work, "Downloading " + download.getDisplayName() + " failed: " + error.getMessage());
            }
        };
        List<Download> started = new ArrayList<>();
        for (ReleaseAsset file : files) {
            Download download = new Download(URI.create(file.downloadUrl()), work.resolve(file.name()));
            download.setDisplayName(file == chosen.asset() ? chosen.variant().label() + " backend " + chosen.tag() : "CUDA libraries");
            if (file.size() > 0) download.setExpectedSize(file.size());
            download.addListener(listener);
            started.add(download);
        }
        downloads = List.copyOf(started);
        for (Download download : started) manager.add(download);
        notifyListeners();
        return null;
    }

    private void install(Offer chosen, Path work) {
        synchronized (lock) {
            if (stage != Stage.DOWNLOADING) return;
            stage = Stage.INSTALLING;
        }
        // Unpacking each download, checking it runs, swapping it in and finding the GPUs.
        int steps = chosen.runtime() != null ? 5 : 4;
        int done = 0;
        step("Unpacking the backend", done++, steps);

        Path staging = App.environment.getDataPath("backend-new").toAbsolutePath().normalize();
        Path backend = App.environment.getDataPath("backend").toAbsolutePath().normalize();
        Path old = App.environment.getDataPath("backend-old").toAbsolutePath().normalize();
        String executable = BackendCatalog.serverExecutableName();

        try {
            deleteTree(staging);
            Files.createDirectories(staging);

            // The libraries go in the same folder as the server, that's where it looks for them.
            Path server = ArchiveExtractor.extract(work.resolve(chosen.asset().name()), staging, executable);
            if (server == null) {
                fail(work, "The download didn't have " + executable + " in it, so nothing was changed.");
                return;
            }
            if (chosen.runtime() != null) {
                step("Unpacking the CUDA libraries", done++, steps);
                ArchiveExtractor.extract(work.resolve(chosen.runtime().name()), staging, executable);
            }

            step("Checking that it runs on this computer", done++, steps);
            String version = InstalledBackend.version(server);
            if (!version.contains("version:")) {
                App.logger.warn("New backend didn't run: {}", version);
                fail(work, "This build won't run on this computer. Your current backend was left as it was.");
                return;
            }
            InstalledBackend.writeMarker(staging, chosen.variant().id(), chosen.build());
        } catch (IOException | RuntimeException e) {
            App.logger.error("Could not unpack the backend", e);
            fail(work, "Couldn't unpack the download: " + e.getMessage());
            return;
        }

        // The old server has its files open on Windows. It's started again by the next message in a chat.
        step("Replacing the old backend", done++, steps);
        ServerProcess.get().stop();
        try {
            deleteTree(old);
            boolean hadOld = Files.exists(backend);
            if (hadOld) Files.move(backend, old);
            try {
                Files.move(staging, backend);
            } catch (IOException e) {
                if (hadOld) Files.move(old, backend); // Put the working one back.
                throw e;
            }
        } catch (IOException e) {
            App.logger.error("Could not swap in the new backend", e);
            fail(work, "Couldn't replace the old backend: " + e.getMessage() + ". Close any open chat and try again.");
            return;
        }

        try {
            deleteTree(old);
            deleteTree(work);
        } catch (IOException e) {
            App.logger.warn("Could not clean up after installing the backend", e); // Harmless, cleared on the next install.
        }

        // The build has to find a GPU to be worth having. If it can't the chat still works, only slowly.
        step("Looking for graphics cards", done, steps);
        String result = "";
        List<HardwareProbe.Device> devices = HardwareProbe.refresh();
        if (!chosen.variant().id().equals("cpu") && devices.isEmpty()) {
            result = "It didn't find a GPU it can use, so chat will run on the processor. Try a different backend if that's not what you expect.";
        }
        App.logger.info("Installed backend '{}' ({})", chosen.variant().id(), chosen.tag());
        synchronized (lock) {
            message = result;
            stage = Stage.DONE;
        }
        notifyListeners();
    }

    private void step(String name, int done, int steps) {
        step = name;
        stepProgress = (double) done / steps;
        notifyListeners();
    }

    private void cancelled(Path work) {
        synchronized (lock) {
            if (stage != Stage.DOWNLOADING) return;
            stage = Stage.IDLE;
            message = "";
        }
        manager.cancelAll(); // The other half of a CUDA build is no use on its own.
        cleanUpLater(work);
        notifyListeners();
    }

    private void fail(Path work, String reason) {
        synchronized (lock) {
            if (stage != Stage.DOWNLOADING && stage != Stage.INSTALLING) return;
            App.logger.error("Backend install failed: {}", reason);
            message = reason;
            stage = Stage.FAILED;
        }
        manager.cancelAll();
        cleanUpLater(work, App.environment.getDataPath("backend-new"));
        notifyListeners();
    }

    // A stopped download is still closing its file when it reports back, so the folders go once they all have.
    private void cleanUpLater(Path... folders) {
        List<Download> mine = downloads;
        cleaning = true;
        Thread.ofVirtual().name("backend-cleanup").start(() -> {
            try {
                for (int i = 0; i < 100 && !mine.stream().allMatch(d -> d.getState().isFinished()); i++) Thread.sleep(100);
                Thread.sleep(200);
                for (Path folder : folders) deleteTree(folder);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                App.logger.warn("Could not clean up after an install that didn't finish", e);
            } finally {
                cleaning = false;
                manager.clearFinished();
                notifyListeners();
            }
        });
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                App.logger.error("A backend listener threw an exception!", e);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }


    private static String format(long bytes) {
        return me.piitex.cca.backend.model.ModelLibrary.formatSize(bytes);
    }
}
