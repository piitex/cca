package me.piitex.cca.backend.local;

import me.piitex.cca.App;
import me.piitex.cca.backend.server.ServerProcess;
import me.piitex.engine.io.AppEnvironment;
import me.piitex.engine.io.download.Download;
import me.piitex.engine.io.download.DownloadListener;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Checks if this device can run a model locally:
 * download the llama.cpp backend, download a small test model, start llama-server and make sure it answers.
 * <p>
 * Everything runs on a background thread. The UI polls {@link #getStage()} and the two downloads every frame.
 * If any step fails it goes straight to FAILED, there's no retry. Make a new instance to try again.
 */
public class LocalSetupService {
    private static final String BACKEND_OWNER = "ggml-org";
    private static final String BACKEND_REPO = "llama.cpp";

    // Qwen2.5 0.5B Q4_K_M, about 380MB. Small and doesn't need a login to download.
    private static final String MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true";
    private static final String MODEL_FILE_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf";

    // Setup runs before the main menu so it can share the chat server's port.
    private static final int SERVER_PORT = ServerProcess.DEFAULT_PORT;
    private static final Duration SERVER_START_TIMEOUT = Duration.ofSeconds(60);

    private final AppEnvironment environment;
    private final List<LocalSetupListener> listeners = new CopyOnWriteArrayList<>();

    private volatile LocalSetupStage stage = LocalSetupStage.IDLE;
    private volatile String errorMessage;

    private volatile Download backendDownload;
    private volatile Download modelDownload;
    private volatile Process serverProcess;

    private volatile Path backendDirectory;
    private volatile Path serverExecutable;
    private volatile Path modelFile;
    private volatile boolean downloadedModel;

    public LocalSetupService(AppEnvironment environment) {
        this.environment = environment;
    }

    public void addListener(LocalSetupListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(LocalSetupListener listener) {
        listeners.remove(listener);
    }

    public LocalSetupStage getStage() {
        return stage;
    }

    // Only set once the stage is FAILED.
    public String getErrorMessage() {
        return errorMessage;
    }

    // Null until the backend download starts.
    public Download getBackendDownload() {
        return backendDownload;
    }

    // Null until the model download starts, and stays null if the model was already downloaded before.
    public Download getModelDownload() {
        return modelDownload;
    }

    public Path getServerExecutable() {
        return serverExecutable;
    }

    public Path getModelFile() {
        return modelFile;
    }

    public void start() {
        if (stage != LocalSetupStage.IDLE) return;
        App.logger.info("Starting local setup...");
        Thread.ofVirtual().name("local-setup").start(this::runBackendDownload);
    }

    private void runBackendDownload() {
        setStage(LocalSetupStage.DOWNLOADING_BACKEND);

        try {
            backendDirectory = environment.getDirectory("backend");
        } catch (IOException e) {
            fail("Could not create the backend directory: " + e.getMessage());
            return;
        }

        LlamaAsset asset;
        try {
            asset = LlamaAsset.forCurrentPlatform();
        } catch (UnsupportedOperationException e) {
            fail(e.getMessage());
            return;
        }

        GitHubReleaseFinder.ReleaseAsset releaseAsset;
        try {
            releaseAsset = new GitHubReleaseFinder(BACKEND_OWNER, BACKEND_REPO).findLatestAsset(asset.assetNameRegex());
        } catch (IOException | InterruptedException e) {
            fail("Could not check for the latest llama.cpp release: " + e.getMessage());
            return;
        }
        if (releaseAsset == null) {
            fail("Could not find a llama.cpp build for this platform.");
            return;
        }
        App.logger.info("Downloading backend '{}'", releaseAsset.name());

        Path archivePath = backendDirectory.resolve(releaseAsset.name());
        Download download = new Download(URI.create(releaseAsset.downloadUrl()), archivePath);
        download.setDisplayName("llama.cpp backend");
        if (releaseAsset.size() > 0) download.setExpectedSize(releaseAsset.size());
        this.backendDownload = download;

        download.addListener(new DownloadListener() {
            @Override
            public void onComplete(Download d) {
                Path resolvedExecutable;
                try {
                    resolvedExecutable = ArchiveExtractor.extract(archivePath, backendDirectory, asset.serverExecutableName());
                    Files.deleteIfExists(archivePath);
                } catch (IOException e) {
                    fail("Could not extract the llama.cpp backend: " + e.getMessage());
                    return;
                }
                if (resolvedExecutable == null) {
                    fail("Extracted backend did not contain " + asset.serverExecutableName() + " (found: " + listDirectory(backendDirectory) + ")");
                    return;
                }
                serverExecutable = resolvedExecutable;
                runModelDownload();
            }

            @Override
            public void onFailed(Download d, Throwable error) {
                fail("Backend download failed: " + error.getMessage());
            }
        });
        download.start();
    }

    private void runModelDownload() {
        setStage(LocalSetupStage.DOWNLOADING_MODEL);

        Path modelsDirectory;
        try {
            modelsDirectory = environment.getDirectory("models");
        } catch (IOException e) {
            fail("Could not create the models directory: " + e.getMessage());
            return;
        }

        Path target = modelsDirectory.resolve(MODEL_FILE_NAME);
        if (Files.exists(target)) {
            // Left over from an earlier attempt, no need to download it again.
            App.logger.info("Test model already downloaded.");
            modelFile = target;
            runServerStart();
            return;
        }

        Download download = new Download(URI.create(MODEL_URL), target);
        download.setDisplayName("Test model");
        this.modelDownload = download;

        download.addListener(new DownloadListener() {
            @Override
            public void onComplete(Download d) {
                modelFile = target;
                downloadedModel = true;
                runServerStart();
            }

            @Override
            public void onFailed(Download d, Throwable error) {
                fail("Test model download failed: " + error.getMessage());
            }
        });
        download.start();
    }

    private void runServerStart() {
        setStage(LocalSetupStage.STARTING_SERVER);

        Path logFile = environment.getDataPath("local-setup-server.log");
        List<String> parameters = List.of(
                serverExecutable.toAbsolutePath().toString(),
                "-m", modelFile.toAbsolutePath().toString(),
                "-c", "512",
                "--port", Integer.toString(SERVER_PORT),
                "--no-webui",
                "-lv", "1"
        );

        ProcessBuilder builder = new ProcessBuilder(parameters);
        builder.directory(backendDirectory.toFile());
        builder.redirectErrorStream(true); // llama-server logs to stderr
        builder.redirectOutput(logFile.toFile());

        try {
            serverProcess = builder.start();
        } catch (IOException e) {
            fail("Could not start llama-server: " + e.getMessage());
            return;
        }

        runVerifyServer(logFile);
    }

    // Success is only decided by /health. The log is only checked for errors so a crash fails fast.
    // Don't wait for a success line in the log: once stdout goes to a file it's block buffered and the
    // log can sit at one line the whole time even though the server is already running fine.
    private void runVerifyServer(Path logFile) {
        setStage(LocalSetupStage.VERIFYING_SERVER);

        HttpClient client = HttpClient.newHttpClient();
        Instant deadline = Instant.now().plus(SERVER_START_TIMEOUT);
        boolean started = false;
        String failureReason = null;

        while (Instant.now().isBefore(deadline)) {
            if (!serverProcess.isAlive()) {
                failureReason = "llama-server exited before it finished starting (exit code " + serverProcess.exitValue() + ")";
                break;
            }
            if (logHasFailureLine(logFile)) {
                failureReason = "llama-server reported an error while starting; see " + logFile;
                break;
            }
            if (isHealthy(client)) {
                started = true;
                break;
            }
            sleep(300);
        }

        if (!started && failureReason == null) {
            failureReason = "llama-server did not become healthy within " + SERVER_START_TIMEOUT.toSeconds() + "s";
        }

        stopServerProcess();

        if (started) {
            App.logger.info("Local setup finished successfully.");
            deleteTestModel();
            setStage(LocalSetupStage.SUCCESS);
            finish(true);
        } else {
            fail(failureReason);
        }
    }

    // The log is only a few lines while this runs, so just read the whole thing each time.
    private boolean logHasFailureLine(Path logFile) {
        if (!Files.exists(logFile)) return false;
        try (Stream<String> lines = Files.lines(logFile)) {
            return lines.anyMatch(line -> isFailureLine(line.trim()));
        } catch (IOException e) {
            return false; // Might be mid write, try again next tick.
        }
    }

    private static boolean isFailureLine(String line) {
        return line.contains("cleaning up before exit...") || line.contains("failed to load model")
                || line.contains("error while handling") || line.startsWith("error:") || line.startsWith("ROCm error:");
    }

    private boolean isHealthy(HttpClient client) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + SERVER_PORT + "/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            return false; // Not accepting connections yet.
        }
    }

    private void stopServerProcess() {
        if (serverProcess == null || !serverProcess.isAlive()) return;
        serverProcess.destroy();
        try {
            if (!serverProcess.waitFor(10, TimeUnit.SECONDS)) {
                serverProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            serverProcess.destroyForcibly();
        }
    }

    private void fail(String reason) {
        App.logger.error("Local setup failed: {}", reason);
        errorMessage = reason;
        stopServerProcess();
        deleteTestModel();
        setStage(LocalSetupStage.FAILED);
        finish(false);
    }

    private void setStage(LocalSetupStage newStage) {
        stage = newStage;
        for (LocalSetupListener listener : listeners) {
            try {
                listener.onStageChanged(newStage);
            } catch (RuntimeException e) {
                App.logger.error("LocalSetupListener threw an exception!", e);
            }
        }
    }

    private void finish(boolean success) {
        for (LocalSetupListener listener : listeners) {
            try {
                listener.onFinished(success);
            } catch (RuntimeException e) {
                App.logger.error("LocalSetupListener threw an exception!", e);
            }
        }
    }

    // The test model is only there to prove the server starts, it isn't something the user picked. A copy that was
    // already in the models folder isn't ours to delete.
    private void deleteTestModel() {
        if (!downloadedModel || modelFile == null) return;
        try {
            Files.deleteIfExists(modelFile);
            App.logger.info("Deleted the test model.");
        } catch (IOException e) {
            App.logger.warn("Could not delete the test model {}", modelFile, e);
        }
        downloadedModel = false;
    }

    // Used in the error message so an unexpected archive layout can be figured out from the log.
    private static String listDirectory(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.map(p -> Files.isDirectory(p) ? p.getFileName() + "/" : p.getFileName().toString())
                    .sorted()
                    .toList()
                    .toString();
        } catch (IOException e) {
            return "<could not list " + directory + ": " + e.getMessage() + ">";
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
