package me.piitex.cca.backend.local;

import me.piitex.engine.utils.Platform;

// Which llama.cpp release to download for this OS and what the server is called inside it.
// Vulkan on Windows/Linux works on most GPUs without having to detect the vendor. macOS gets the Metal build.
public record LlamaAsset(String assetNameRegex, String serverExecutableName) {

    public static LlamaAsset forCurrentPlatform() {
        return switch (Platform.getCurrent()) {
            case WINDOWS -> new LlamaAsset("llama-[a-zA-Z0-9]+-bin-win-vulkan-x64\\.zip", "llama-server.exe");
            case LINUX -> new LlamaAsset("llama-[a-zA-Z0-9]+-bin-ubuntu-vulkan-x64\\.tar\\.gz", "llama-server");
            case MACOS -> new LlamaAsset("llama-[a-zA-Z0-9]+-bin-macos-arm64\\.tar\\.gz", "llama-server");
            case OTHER -> throw new UnsupportedOperationException("No llama.cpp backend build available for this platform.");
        };
    }
}
