package me.piitex.cca.backend.server;

import me.piitex.cca.App;
import me.piitex.cca.backend.local.LlamaAsset;
import me.piitex.cca.backend.model.GgufInfo;
import me.piitex.cca.backend.model.HardwareProbe;
import me.piitex.cca.backend.model.LayerPlanner;
import me.piitex.cca.backend.model.ModelLibrary;
import me.piitex.cca.backend.model.ServerArgs;
import me.piitex.cca.config.AppConfig;
import me.piitex.cca.config.ModelSettings;

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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The llama-server that generates chat replies. There's only one ({@link #get()}).
 * <p>
 * start() launches it on a background thread and returns right away. getState() is safe to check from
 * the render thread. It's ready once /health returns 200 (503 means the model is still loading).
 * The PID is saved to app.conf so a server left behind by a crash can be shut down on the next launch.
 */
public final class ServerProcess {
    public static final int DEFAULT_PORT = 8187;

    public enum State {STOPPED, STARTING, READY, FAILED}

    private static final ServerProcess INSTANCE = new ServerProcess();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private volatile State state = State.STOPPED;
    private volatile String errorMessage;
    private volatile Process process;
    private volatile Path modelFile;
    private volatile LayerPlanner.Plan plan;

    private ServerProcess() {
        // Makes sure the server is closed with the app.
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "llama-server-shutdown"));
    }

    public static ServerProcess get() {
        return INSTANCE;
    }

    public State getState() {
        return state;
    }

    // Why the last start failed.
    public String getErrorMessage() {
        return errorMessage;
    }

    // The model the server loaded. Null before the first start.
    public Path getModelFile() {
        return modelFile;
    }

    // How the model was split between the GPU and system memory. Null if it couldn't be planned.
    public LayerPlanner.Plan getPlan() {
        return plan;
    }

    public String getBaseUrl() {
        ModelSettings settings = App.instance.getModelSettings();
        String host = settings.effectiveHost().split(",")[0].trim();
        if (host.isEmpty() || host.equals("0.0.0.0") || host.equals("::")) host = "localhost";
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return "http://" + host + ":" + settings.port.get();
    }

    // Does nothing if it's already starting or running. After a failure this tries again,
    // so the next chat message acts as a retry.
    public synchronized void start() {
        if (state == State.READY && process != null && !process.isAlive()) {
            App.logger.warn("llama-server exited unexpectedly (exit code {}), restarting...", process.exitValue());
            state = State.STOPPED;
        }
        if (state == State.STARTING || state == State.READY) return;
        state = State.STARTING;
        errorMessage = null;
        Thread.ofVirtual().name("llama-server-start").start(this::launch);
    }

    /**
     * Starts the server if needed and blocks until it's ready or failed.
     * Only call this from a worker thread, never the render thread.
     *
     * @return true if the server is ready
     */
    public boolean awaitReady() throws InterruptedException {
        start();
        while (true) {
            State current = state;
            if (current == State.READY) return true;
            if (current == State.FAILED || current == State.STOPPED) return false;
            Thread.sleep(200);
        }
    }

    public synchronized void stop() {
        Process running = process;
        process = null;
        if (running != null && running.isAlive()) {
            App.logger.info("Stopping llama-server...");
            running.destroy();
            try {
                if (!running.waitFor(10, TimeUnit.SECONDS)) {
                    running.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.destroyForcibly();
            }
            // Closed properly so there's nothing to clean up next launch.
            App.instance.getAppConfig().setServerPid(0);
        }
        if (state != State.FAILED) state = State.STOPPED;
    }

    private void launch() {
        AppConfig config = App.instance.getAppConfig();
        stopOrphanedServer(config);

        Path executable = App.environment.getDataPath("backend").resolve(LlamaAsset.forCurrentPlatform().serverExecutableName());
        if (!Files.isRegularFile(executable)) {
            fail("llama-server was not found at " + executable + ". Run local setup again.");
            return;
        }
        if (executable.toFile().setExecutable(true, true)) {
            App.logger.debug("Updated file permissions for llama-server.");
        }

        ModelSettings settings = App.instance.getModelSettings();
        Path model = ModelLibrary.resolve(settings);
        if (model == null) {
            fail("No model found. Add a .gguf file to " + ModelLibrary.directory() + " and choose it in the Models tab.");
            return;
        }
        modelFile = model;
        plan = planLayers(model, settings);

        List<String> parameters = ServerArgs.build(executable, model, settings, plan);
        App.logger.info("Starting llama-server: {}", parameters);

        Path logFile = App.environment.getDataPath("server.log");
        ProcessBuilder builder = new ProcessBuilder(parameters);
        builder.directory(executable.getParent().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        // Should fix BSOD with vulkan on some Windows GPUs.
        builder.environment().put("GGML_VK_DISABLE_HOST_VISIBLE_VIDMEM", "1");
        // Passed as an env variable instead of --api-key so it doesn't show up in the process list.
        if (!settings.getServerApiKey().isEmpty()) {
            builder.environment().put("LLAMA_API_KEY", settings.getServerApiKey());
        }

        Process started;
        try {
            started = builder.start();
        } catch (IOException e) {
            fail("Could not start llama-server: " + e.getMessage());
            return;
        }
        process = started;

        // Save PID to terminate the process later.
        config.setServerPid(started.pid());

        Duration startTimeout = Duration.ofSeconds(Math.max(10, settings.loadTimeoutSeconds.get()));
        Instant deadline = Instant.now().plus(startTimeout);
        while (Instant.now().isBefore(deadline)) {
            if (!started.isAlive()) {
                fail("llama-server exited while starting (exit code " + started.exitValue() + "); see " + logFile);
                return;
            }
            if (isHealthy()) {
                App.logger.info("Started llama-server successfully with {}", model.getFileName());
                state = State.READY;
                return;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop();
                fail("Interrupted while waiting for llama-server");
                return;
            }
        }
        stop();
        fail("llama-server did not finish loading within " + startTimeout.toSeconds() + "s; raise the load timeout in Models > Server, or see " + logFile);
    }

    // Works out the GPU layers from the GPU usage setting. Null lets llama.cpp decide.
    private static LayerPlanner.Plan planLayers(Path model, ModelSettings settings) {
        long gpuMb = HardwareProbe.gpuMemoryMb(settings);
        if (gpuMb <= 0) return null;
        try {
            LayerPlanner.Plan plan = LayerPlanner.plan(GgufInfo.read(model), settings, gpuMb);
            if (plan != null) {
                App.logger.info("Using '{}%' of the GPU to load '{}'/'{}' layers ({} MiB of {} MiB)", settings.gpuUsage.get(),
                        plan.gpuLayers(), plan.totalLayers(), Math.round(plan.gpuMb()), gpuMb);
            }
            return plan;
        } catch (IOException e) {
            App.logger.warn("Could not read {} to plan GPU layers, letting llama.cpp decide.", model.getFileName(), e);
            return null;
        }
    }

    // Only kills the old process if it still looks like llama-server, the PID could have been reused by something else.
    private static void stopOrphanedServer(AppConfig config) {
        long pid = config.getServerPid();
        if (pid <= 0) return;
        App.logger.info("Previous PID: {}", pid);
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isPresent() && handle.get().isAlive()) {
            String command = handle.get().info().command().orElse("");
            if (command.contains("llama-server")) {
                App.logger.info("Stopping llama-server left over from a previous run (PID {})", pid);
                handle.get().destroy();
                try {
                    handle.get().onExit().get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    App.logger.info("Process is still alive. Destroying {} forcefully.", pid);
                    handle.get().destroyForcibly();
                }
            }
        }
        config.setServerPid(0);
    }

    private boolean isHealthy() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(getBaseUrl() + "/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false; // Not accepting connections yet.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void fail(String reason) {
        App.logger.error("llama-server failed: {}", reason);
        errorMessage = reason;
        state = State.FAILED;
    }
}
