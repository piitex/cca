package me.piitex.cca.backend.local;

// One kind of llama.cpp build. The regexes find its files in a release, runtimeRegex is the extra download
// some builds need (the CUDA libraries) and is null for the rest.
public record BackendVariant(String id, String label, String blurb, Vendor vendor, String assetRegex, String runtimeRegex) {

    // Whose GPU a build is made for. NONE runs anywhere.
    public enum Vendor {NONE, APPLE, NVIDIA, AMD, INTEL}

    // The CUDA version in the id (cuda-12, cuda-13), or 0 for the rest.
    public int cudaMajor() {
        return id.startsWith("cuda-") ? Integer.parseInt(id.substring("cuda-".length())) : 0;
    }
}
