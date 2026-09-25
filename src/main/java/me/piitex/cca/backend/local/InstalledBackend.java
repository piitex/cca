package me.piitex.cca.backend.local;

import me.piitex.cca.App;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What's in the backend folder. Installing from the Updates screen leaves a small file saying which build it is.
 * A backend from the first run setup doesn't have one, so its kind is worked out from the libraries in the
 * folder and its build number from asking llama-server. Asking starts a process, so keep it off the render thread.
 */
public record InstalledBackend(String variantId, int build) {
    private static final String MARKER = "cca-backend.json";
    private static final Pattern VERSION = Pattern.compile("build (\\d+)");
    private static final Pattern CUDA_WINDOWS = Pattern.compile("cudart64_(\\d+)");
    private static final Pattern CUDA_LINUX = Pattern.compile("libcudart\\.so\\.(\\d+)");

    // Null if there's no working llama-server in the folder.
    public static InstalledBackend read(Path directory) {
        Path executable = directory.resolve(BackendCatalog.serverExecutableName());
        if (!Files.isRegularFile(executable)) return null;

        try {
            Path marker = directory.resolve(MARKER);
            if (Files.isRegularFile(marker)) {
                JSONObject json = new JSONObject(Files.readString(marker));
                return new InstalledBackend(json.getString("variant"), json.getInt("build"));
            }
        } catch (IOException | RuntimeException e) {
            App.logger.warn("Could not read {}, working the backend out from its files instead.", MARKER, e);
        }
        return new InstalledBackend(infer(directory), build(executable));
    }

    public static void writeMarker(Path directory, String variantId, int build) throws IOException {
        JSONObject json = new JSONObject().put("variant", variantId).put("build", build);
        Files.writeString(directory.resolve(MARKER), json.toString(2));
    }

    // The number llama-server reports, like 11173 from "version: 0.5.0-dev (build 11173, commit 84e76d8a2)". 0 if it wouldn't say.
    public static int build(Path executable) {
        Matcher matcher = VERSION.matcher(version(executable));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    // What "llama-server --version" prints, empty if it doesn't run.
    public static String version(Path executable) {
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.toAbsolutePath().toString(), "--version");
            builder.directory(executable.toAbsolutePath().getParent().toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            Thread.startVirtualThread(() -> {
                try {
                    if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                }
            });
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // Every build has its GPU support in a library named for it (libggml-metal, ggml-cuda.dll), the CPU one has none.
    private static String infer(Path directory) {
        List<String> names;
        try (Stream<Path> files = Files.list(directory)) {
            names = files.map(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)).toList();
        } catch (IOException e) {
            return "cpu";
        }
        if (has(names, "ggml-metal")) return "metal";
        if (has(names, "ggml-cuda")) return "cuda-" + cudaMajor(names);
        if (has(names, "ggml-hip") || has(names, "ggml-rocm")) return "rocm";
        if (has(names, "ggml-sycl")) return "sycl";
        if (has(names, "ggml-opencl")) return "opencl";
        if (has(names, "ggml-vulkan")) return "vulkan";
        return "cpu";
    }

    private static boolean has(List<String> names, String part) {
        return names.stream().anyMatch(name -> name.contains(part));
    }

    private static int cudaMajor(List<String> names) {
        for (String name : names) {
            Matcher windows = CUDA_WINDOWS.matcher(name);
            if (windows.find()) return Integer.parseInt(windows.group(1));
            Matcher linux = CUDA_LINUX.matcher(name);
            if (linux.find()) return Integer.parseInt(linux.group(1));
        }
        return 12;
    }
}
