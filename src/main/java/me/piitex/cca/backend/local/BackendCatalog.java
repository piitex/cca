package me.piitex.cca.backend.local;

import me.piitex.cca.backend.local.BackendVariant.Vendor;
import me.piitex.engine.utils.Platform;

import java.util.List;
import java.util.Locale;

// Which llama.cpp builds exist for this OS and processor. The names are how the ggml-org releases are named,
// so a build that gets renamed upstream shows up as unavailable until its pattern is updated here.
public final class BackendCatalog {
    private static final String BUILD = "llama-b\\d+-bin-";
    private static final String RUNTIME = "cudart-llama-";

    private BackendCatalog() {
    }

    public static List<BackendVariant> forCurrentSystem() {
        boolean arm = isArm();
        return switch (Platform.getCurrent()) {
            case WINDOWS -> arm ? windowsArm() : windowsX64();
            case LINUX -> arm ? linuxArm() : linuxX64();
            case MACOS -> List.of(arm
                    ? new BackendVariant("metal", "Metal", "For Apple Silicon. Uses the GPU and shares the computer's memory.", Vendor.APPLE, BUILD + "macos-arm64\\.tar\\.gz", null)
                    : new BackendVariant("cpu", "CPU", "For Intel Macs. Runs on the processor.", Vendor.NONE, BUILD + "macos-x64\\.tar\\.gz", null));
            case OTHER -> List.of();
        };
    }

    // What the server is called in every build.
    public static String serverExecutableName() {
        return Platform.getCurrent() == Platform.WINDOWS ? "llama-server.exe" : "llama-server";
    }

    public static boolean isArm() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm");
    }

    private static List<BackendVariant> windowsX64() {
        return List.of(
                vulkan("win-vulkan-x64\\.zip"),
                new BackendVariant("cuda-13", "CUDA 13", "For NVIDIA GPUs with a recent driver. Includes the CUDA libraries.", Vendor.NVIDIA,
                        BUILD + "win-cuda-13\\.\\d+-x64\\.zip", RUNTIME + "bin-win-cuda-13\\.\\d+-x64\\.zip"),
                new BackendVariant("cuda-12", "CUDA 12", "For NVIDIA GPUs with an older driver. Includes the CUDA libraries.", Vendor.NVIDIA,
                        BUILD + "win-cuda-12\\.\\d+-x64\\.zip", RUNTIME + "bin-win-cuda-12\\.\\d+-x64\\.zip"),
                new BackendVariant("rocm", "ROCm (HIP)", "Made for AMD GPUs. Vulkan is the safer choice unless this is known to work for yours.", Vendor.AMD,
                        BUILD + "win-(?:rocm|hip)[a-z0-9.-]*-x64\\.zip", null),
                new BackendVariant("sycl", "SYCL", "Made for Intel GPUs.", Vendor.INTEL, BUILD + "win-sycl-x64\\.zip", null),
                cpu("win-cpu-x64\\.zip"));
    }

    private static List<BackendVariant> windowsArm() {
        return List.of(
                new BackendVariant("opencl", "OpenCL (Adreno)", "For Snapdragon computers, uses the Adreno GPU.", Vendor.NONE, BUILD + "win-opencl-adreno-arm64\\.zip", null),
                cpu("win-cpu-arm64\\.zip"));
    }

    private static List<BackendVariant> linuxX64() {
        return List.of(
                vulkan("ubuntu-vulkan-x64\\.tar\\.gz"),
                new BackendVariant("cuda-13", "CUDA 13", "For NVIDIA GPUs with a recent driver. Includes the CUDA libraries.", Vendor.NVIDIA,
                        BUILD + "ubuntu-cuda-13\\.\\d+-x64\\.tar\\.gz", RUNTIME + "b\\d+-bin-ubuntu-cuda-13\\.\\d+-x64\\.tar\\.gz"),
                new BackendVariant("cuda-12", "CUDA 12", "For NVIDIA GPUs with an older driver. Includes the CUDA libraries.", Vendor.NVIDIA,
                        BUILD + "ubuntu-cuda-12\\.\\d+-x64\\.tar\\.gz", RUNTIME + "b\\d+-bin-ubuntu-cuda-12\\.\\d+-x64\\.tar\\.gz"),
                new BackendVariant("rocm", "ROCm", "Made for AMD GPUs and needs ROCm installed. Vulkan is the safer choice.", Vendor.AMD,
                        BUILD + "ubuntu-rocm-[0-9.]+-x64\\.tar\\.gz", null),
                new BackendVariant("sycl", "SYCL", "Made for Intel GPUs and needs oneAPI installed.", Vendor.INTEL, BUILD + "ubuntu-sycl-fp16-x64\\.tar\\.gz", null),
                cpu("ubuntu-x64\\.tar\\.gz"));
    }

    private static List<BackendVariant> linuxArm() {
        return List.of(
                vulkan("ubuntu-vulkan-arm64\\.tar\\.gz"),
                new BackendVariant("cuda-13", "CUDA 13", "For NVIDIA GPUs. Includes the CUDA libraries.", Vendor.NVIDIA,
                        BUILD + "ubuntu-cuda-13\\.\\d+-arm64\\.tar\\.gz", RUNTIME + "b\\d+-bin-ubuntu-cuda-13\\.\\d+-arm64\\.tar\\.gz"),
                cpu("ubuntu-arm64\\.tar\\.gz"));
    }

    private static BackendVariant vulkan(String suffix) {
        return new BackendVariant("vulkan", "Vulkan", "Works on most GPUs from any maker. A good choice when unsure.", Vendor.NONE, BUILD + suffix, null);
    }

    private static BackendVariant cpu(String suffix) {
        return new BackendVariant("cpu", "CPU", "Runs on the processor only. Slow, but works anywhere.", Vendor.NONE, BUILD + suffix, null);
    }
}
