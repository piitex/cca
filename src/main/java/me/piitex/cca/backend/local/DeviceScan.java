package me.piitex.cca.backend.local;

import me.piitex.cca.App;
import me.piitex.cca.backend.local.BackendVariant.Vendor;
import me.piitex.engine.utils.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which GPUs this computer has, asked of the operating system. That's what decides which backend to recommend.
 * It runs commands, so it takes a moment and belongs on a background thread. The result is kept until refresh().
 * Anything that can't be read is just left out, the user can still pick any backend by hand.
 */
public final class DeviceScan {

    // cudaMajor is the newest CUDA the NVIDIA driver supports, 0 if there isn't one.
    public record Result(Set<Vendor> vendors, List<String> names, int cudaMajor, String cudaVersion) {
        public boolean has(Vendor vendor) {
            return vendors.contains(vendor);
        }
    }

    private static final Pattern CUDA_VERSION = Pattern.compile("CUDA Version:\\s*(\\d+)\\.(\\d+)");
    private static final Path DRM = Path.of("/sys/class/drm");

    private static Result cached;

    private DeviceScan() {
    }

    public static synchronized Result get() {
        if (cached == null) cached = scan();
        return cached;
    }

    // Null if nothing has scanned yet. For callers that can't wait.
    public static synchronized Result cachedResult() {
        return cached;
    }

    public static synchronized Result refresh() {
        cached = scan();
        return cached;
    }

    private static Result scan() {
        Set<Vendor> vendors = EnumSet.noneOf(Vendor.class);
        List<String> names = new ArrayList<>();

        switch (Platform.getCurrent()) {
            case WINDOWS -> names.addAll(lines(run("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_VideoController).Name")));
            case MACOS -> {
                if (System.getProperty("os.arch", "").contains("aarch64")) {
                    vendors.add(Vendor.APPLE);
                    String chip = run("sysctl", "-n", "machdep.cpu.brand_string").trim();
                    names.add(chip.isEmpty() ? "Apple Silicon" : chip);
                }
            }
            case LINUX -> {
                vendors.addAll(linuxVendors());
                for (String line : lines(run("lspci"))) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.contains("vga compatible") || lower.contains("3d controller") || lower.contains("display controller")) {
                        names.add(line.substring(line.indexOf(": ") >= 0 ? line.indexOf(": ") + 2 : 0).trim());
                    }
                }
            }
            case OTHER -> {
            }
        }
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("nvidia")) vendors.add(Vendor.NVIDIA);
            if (lower.contains("amd") || lower.contains("radeon") || lower.contains("advanced micro")) vendors.add(Vendor.AMD);
            if (lower.contains("intel")) vendors.add(Vendor.INTEL);
        }

        // The driver says which CUDA it can run, and that's what picks between the CUDA builds.
        int cudaMajor = 0;
        String cudaVersion = "";
        Matcher cuda = CUDA_VERSION.matcher(run("nvidia-smi"));
        if (cuda.find()) {
            vendors.add(Vendor.NVIDIA);
            cudaMajor = Integer.parseInt(cuda.group(1));
            cudaVersion = cuda.group(1) + "." + cuda.group(2);
        }

        App.logger.info("GPU scan found {} (CUDA {})", names, cudaVersion.isEmpty() ? "none" : cudaVersion);
        return new Result(Set.copyOf(vendors), List.copyOf(names), cudaMajor, cudaVersion);
    }

    // The PCI vendor ids of the display devices, the same list lspci reads.
    private static Set<Vendor> linuxVendors() {
        Set<Vendor> found = EnumSet.noneOf(Vendor.class);
        if (!Files.isDirectory(DRM)) return found;
        try (var cards = Files.list(DRM)) {
            for (Path card : cards.toList()) {
                Path vendorFile = card.resolve("device/vendor");
                if (!Files.isRegularFile(vendorFile)) continue;
                switch (Files.readString(vendorFile).trim().toLowerCase(Locale.ROOT)) {
                    case "0x10de" -> found.add(Vendor.NVIDIA);
                    case "0x1002" -> found.add(Vendor.AMD);
                    case "0x8086" -> found.add(Vendor.INTEL);
                    default -> {
                    }
                }
            }
        } catch (IOException e) {
            App.logger.debug("Could not read the display devices", e);
        }
        return found;
    }

    private static List<String> lines(String output) {
        return output.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    // The command's output, or nothing if it isn't installed, fails or takes too long.
    private static String run(String... command) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();
            Process running = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            }, Thread::startVirtualThread);
            return output.get(10, TimeUnit.SECONDS);
        } catch (IOException e) {
            return ""; // Not installed.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            App.logger.debug("'{}' didn't finish", command[0]);
            return "";
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
