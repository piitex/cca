package me.piitex.cca.backend.model;

import me.piitex.cca.config.ModelSettings;

/**
 * Turns "use N% of my GPU" into the -ngl layer count by working out what each layer costs in bytes.
 * <p>
 * A layer costs its weights plus (when the KV cache is offloaded) its share of the KV cache. The budget
 * is gpuMemory * percent but never more than what's left after the headroom, so 100% means everything
 * that safely fits. 16GB of GPU and a 16GB model at 50% puts about half the layers on the GPU.
 * <p>
 * llama.cpp puts the output layer on the GPU first, then fills from the last block backwards, so the
 * plan does the same. These are estimates (compute buffers aren't counted), the headroom covers that.
 */
public final class LayerPlanner {
    private static final long MIB = 1024L * 1024L;

    /**
     * @param gpuLayerArg what to pass as -ngl (the blocks, plus one when the output layer is on the GPU)
     * @param kvEstimated true when the model file didn't have the attention shape so the KV size is a guess
     */
    public record Plan(
            int totalLayers,
            int gpuLayers,
            int gpuLayerArg,
            long gpuBytes,
            long systemBytes,
            long budgetBytes,
            long gpuMemoryBytes,
            long avgLayerWeightBytes,
            long avgLayerKvBytes,
            long kvTotalBytes,
            long outputBytes,
            boolean fitsEntirely,
            boolean kvEstimated
    ) {
        public long costPerLayerBytes(boolean kvOnGpu) {
            return avgLayerWeightBytes + (kvOnGpu ? avgLayerKvBytes : 0);
        }

        public double gpuMb() {
            return gpuBytes / (double) MIB;
        }

        public double systemMb() {
            return systemBytes / (double) MIB;
        }
    }

    private LayerPlanner() {
    }

    // Returns null if the model doesn't report any layers.
    public static Plan plan(GgufInfo info, ModelSettings settings, long gpuMemoryMb) {
        int n = info.layers();
        if (n <= 0) return null;

        int contextSize = settings.contextSize.get();
        double bytesK = bytesPerElement(settings.cacheTypeK.get());
        double bytesV = bytesPerElement(settings.cacheTypeV.get());
        boolean kvOnGpu = settings.kvOffload.get();

        long[] kv = new long[n];
        long[] cost = new long[n];
        long kvTotal = 0, weightTotal = 0;
        for (int i = 0; i < n; i++) {
            kv[i] = info.kvBytes(i, contextSize, bytesK, bytesV);
            kvTotal += kv[i];
            weightTotal += info.layerBytes()[i];
            cost[i] = info.layerBytes()[i] + (kvOnGpu ? kv[i] : 0);
        }

        long gpuMemory = gpuMemoryMb * MIB;
        long headroom = (long) settings.gpuHeadroomMb.get() * MIB;
        double percent = Math.max(0, Math.min(100, settings.gpuUsage.get()));
        long budget = Math.min((long) (gpuMemory * percent / 100.0), Math.max(0, gpuMemory - headroom));

        int layersOnGpu = 0;
        long gpuBytes = 0;
        boolean outputOnGpu = false;
        if (budget > 0 && info.outputBytes() <= budget) {
            outputOnGpu = true;
            long remaining = budget - info.outputBytes();
            gpuBytes = info.outputBytes();
            for (int i = n - 1; i >= 0 && cost[i] <= remaining; i--) {
                remaining -= cost[i];
                gpuBytes += cost[i];
                layersOnGpu++;
            }
        }

        // Everything else is in system memory, including the input embeddings and the KV cache of CPU layers.
        long systemBytes = info.inputBytes() + info.otherBytes() + (outputOnGpu ? 0 : info.outputBytes());
        for (int i = 0; i < n; i++) {
            boolean onGpu = i >= n - layersOnGpu;
            systemBytes += onGpu ? (kvOnGpu ? 0 : kv[i]) : info.layerBytes()[i] + kv[i];
        }

        return new Plan(n, layersOnGpu, outputOnGpu ? layersOnGpu + 1 : 0, gpuBytes, systemBytes,
                budget, gpuMemory, weightTotal / n, kvTotal / n, kvTotal, info.outputBytes(),
                outputOnGpu && layersOnGpu == n, !info.kvKnown());
    }

    // Quantized types store a scale for every 32 elements so they aren't a whole number of bits.
    public static double bytesPerElement(String cacheType) {
        return switch (cacheType) {
            case "f32" -> 4.0;
            case "q8_0" -> 34.0 / 32;
            case "q5_1" -> 24.0 / 32;
            case "q5_0" -> 22.0 / 32;
            case "q4_1" -> 20.0 / 32;
            case "q4_0", "iq4_nl" -> 18.0 / 32;
            default -> 2.0; // f16, bf16
        };
    }
}
