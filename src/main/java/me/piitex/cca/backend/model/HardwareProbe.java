package me.piitex.cca.backend.model;

import me.piitex.cca.App;
import me.piitex.cca.backend.local.LlamaAsset;
import me.piitex.cca.config.ModelSettings;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The GPUs come from asking llama-server itself (--list-devices), so they're exactly what llama.cpp will
 * use whatever the backend is (Metal, CUDA, Vulkan). System memory comes from the JVM.
 * Probing starts a process so the result is cached until refresh(). Keep it off the render thread.
 */
public final class HardwareProbe {

    // Sizes are in MB.
    public record Device(String name, String description, long totalMb, long freeMb) {
        public String label() {
            return description.isEmpty() ? name : name + " - " + description;
        }
    }

    private static final Pattern DEVICE_LINE = Pattern.compile("^\\s*([^:\\s]+):\\s*(.*?)\\s*\\((\\d+) MiB, (\\d+) MiB free\\)\\s*$");

    private static List<Device> cached;

    private HardwareProbe() {
    }

    // Empty if there are none or the server isn't installed yet.
    public static synchronized List<Device> gpus() {
        if (cached == null) cached = probe();
        return cached;
    }

    // Null if nothing has probed yet. For callers that can't wait.
    public static synchronized List<Device> cachedGpus() {
        return cached;
    }

    public static synchronized List<Device> refresh() {
        cached = probe();
        return cached;
    }

    // The override if one is set, otherwise the selected device, otherwise all GPUs added up. 0 if unknown.
    public static long gpuMemoryMb(ModelSettings settings) {
        return gpuMemoryMb(settings, gpus());
    }

    public static long gpuMemoryMb(ModelSettings settings, List<Device> devices) {
        if (settings.gpuMemoryMb.get() > 0) return settings.gpuMemoryMb.get();
        long total = 0;
        for (Device device : devices) {
            if (settings.device.get().isBlank() || settings.device.get().equals(device.name())) {
                total += device.totalMb();
            }
        }
        return total;
    }

    // This computer's local network address (192.168.x.x) for sharing the server. Null if not on a network.
    public static String lanAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            App.logger.warn("Could not read network interfaces!", e);
        }
        return null;
    }

    // Total RAM in MB, or 0 if the JVM can't tell.
    public static long systemMemoryMb() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getTotalMemorySize() / (1024 * 1024);
        }
        return 0;
    }

    private static List<Device> probe() {
        Path executable = App.environment.getDataPath("backend").resolve(LlamaAsset.forCurrentPlatform().serverExecutableName());
        if (!Files.isRegularFile(executable)) return List.of();

        try {
            ProcessBuilder builder = new ProcessBuilder(executable.toAbsolutePath().toString(), "--list-devices");
            builder.directory(executable.getParent().toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly();

            List<Device> devices = new ArrayList<>();
            for (String line : new String(output, StandardCharsets.UTF_8).split("\\R")) {
                Matcher m = DEVICE_LINE.matcher(line);
                if (!m.matches()) continue;
                long total = Long.parseLong(m.group(3));
                if (total <= 0) continue; // BLAS and other CPU backends report no memory.
                devices.add(new Device(m.group(1), m.group(2), total, Long.parseLong(m.group(4))));
            }
            App.logger.info("Found {} GPU device(s).", devices.size());
            return List.copyOf(devices);
        } catch (IOException e) {
            App.logger.warn("Could not list llama.cpp devices!", e);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
