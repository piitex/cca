package me.piitex.cca.backend.model;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what we need from a .gguf header without loading the weights. The important part is how many
 * bytes each layer takes, that's what lets LayerPlanner turn a percentage into a layer count.
 * <p>
 * Tensor sizes come from the gap between one tensor's offset and the next, so they're exact for every
 * quant type without needing a size table. Split models (name-00001-of-00003.gguf) are read as one.
 * The tokenizer arrays are skipped so this is fast, but it's still file I/O so keep it off the render thread.
 *
 * @param blockCount  number of layers
 * @param layerBytes  weight bytes of each layer
 * @param inputBytes  the token embedding table (stays in system memory)
 * @param outputBytes the output layer and final norm (goes to the GPU with the first offloaded layer)
 * @param headCountKv KV heads per layer, 0 for layers without attention
 * @param kvKnown     false when the header didn't have enough to size the KV cache, so it's estimated
 */
public record GgufInfo(
        Path path,
        long fileBytes,
        String architecture,
        String name,
        String quantization,
        int blockCount,
        long trainingContext,
        long embeddingLength,
        long[] layerBytes,
        long inputBytes,
        long outputBytes,
        long otherBytes,
        long[] headCountKv,
        long keyLength,
        long valueLength,
        boolean expertModel,
        boolean kvKnown
) {

    private static final Pattern SHARD = Pattern.compile("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_TENSOR = Pattern.compile("^blk\\.(\\d+)\\..*");

    private static final Map<Long, String> FILE_TYPES = Map.ofEntries(
            Map.entry(0L, "F32"), Map.entry(1L, "F16"), Map.entry(2L, "Q4_0"), Map.entry(3L, "Q4_1"),
            Map.entry(7L, "Q8_0"), Map.entry(8L, "Q5_0"), Map.entry(9L, "Q5_1"), Map.entry(10L, "Q2_K"),
            Map.entry(11L, "Q3_K_S"), Map.entry(12L, "Q3_K_M"), Map.entry(13L, "Q3_K_L"),
            Map.entry(14L, "Q4_K_S"), Map.entry(15L, "Q4_K_M"), Map.entry(16L, "Q5_K_S"),
            Map.entry(17L, "Q5_K_M"), Map.entry(18L, "Q6_K"), Map.entry(19L, "IQ2_XXS"),
            Map.entry(20L, "IQ2_XS"), Map.entry(21L, "Q2_K_S"), Map.entry(22L, "IQ3_XS"),
            Map.entry(23L, "IQ3_XXS"), Map.entry(24L, "IQ1_S"), Map.entry(25L, "IQ4_NL"),
            Map.entry(26L, "IQ3_S"), Map.entry(27L, "IQ3_M"), Map.entry(28L, "IQ2_S"),
            Map.entry(29L, "IQ2_M"), Map.entry(30L, "IQ4_XS"), Map.entry(31L, "IQ1_M"),
            Map.entry(32L, "BF16"), Map.entry(36L, "TQ1_0"), Map.entry(37L, "TQ2_0"),
            Map.entry(38L, "MXFP4"));

    private static final Map<Path, GgufInfo> CACHE = new HashMap<>();

    // Everything after -00001-of-N. The model list hides these since loading the first shard loads them all.
    public static boolean isSecondaryShard(Path file) {
        Matcher m = SHARD.matcher(file.getFileName().toString());
        return m.matches() && Integer.parseInt(m.group(2)) > 1;
    }

    // Size on disk across all shards.
    public static long totalBytes(Path file) {
        long total = 0;
        for (Path shard : shards(file)) {
            try {
                total += Files.size(shard);
            } catch (IOException ignored) {
                // A missing shard is reported when the model is actually loaded.
            }
        }
        return total;
    }

    // Cached until the file size changes.
    public static synchronized GgufInfo read(Path file) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        GgufInfo cached = CACHE.get(key);
        if (cached != null && cached.fileBytes == totalBytes(key)) return cached;
        GgufInfo info = parse(key);
        CACHE.put(key, info);
        return info;
    }

    public int layers() {
        return blockCount;
    }

    public long averageLayerBytes() {
        if (blockCount == 0) return 0;
        long sum = 0;
        for (long b : layerBytes) sum += b;
        return sum / blockCount;
    }

    // KV cache bytes one layer needs at the given context size.
    public long kvBytes(int block, int contextSize, double bytesPerElementK, double bytesPerElementV) {
        long heads = headCountKv[Math.min(block, headCountKv.length - 1)];
        return (long) (contextSize * heads * (keyLength * bytesPerElementK + valueLength * bytesPerElementV));
    }

    private static List<Path> shards(Path file) {
        Matcher m = SHARD.matcher(file.getFileName().toString());
        if (!m.matches()) return List.of(file);
        int count = Integer.parseInt(m.group(3));
        List<Path> shards = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            shards.add(file.resolveSibling("%s-%05d-of-%05d.gguf".formatted(m.group(1), i, count)));
        }
        return shards;
    }

    private record Tensor(String name, long offset) {
    }

    private static GgufInfo parse(Path file) throws IOException {
        Map<String, Object> meta = new HashMap<>();
        Map<Integer, Long> blockBytes = new HashMap<>();
        long input = 0, output = 0, other = 0, total = 0;
        boolean tiedOutput = true;
        long tokenEmbd = 0;

        List<Path> shards = shards(file);
        for (Path shard : shards) {
            long size = Files.size(shard);
            total += size;
            Map<String, Object> shardMeta = new HashMap<>();
            List<Tensor> tensors = new ArrayList<>();
            long dataStart = readHeader(shard, shardMeta, tensors);
            if (shard.equals(shards.getFirst())) meta = shardMeta;

            tensors.sort(Comparator.comparingLong(Tensor::offset));
            for (int i = 0; i < tensors.size(); i++) {
                Tensor t = tensors.get(i);
                long end = i + 1 < tensors.size() ? tensors.get(i + 1).offset() : size - dataStart;
                long bytes = Math.max(0, end - t.offset());

                Matcher m = BLOCK_TENSOR.matcher(t.name());
                if (m.matches()) {
                    blockBytes.merge(Integer.parseInt(m.group(1)), bytes, Long::sum);
                } else if (t.name().equals("token_embd.weight")) {
                    input += bytes;
                    tokenEmbd += bytes;
                } else if (t.name().startsWith("output")) {
                    output += bytes;
                    if (t.name().equals("output.weight")) tiedOutput = false;
                } else {
                    other += bytes;
                }
            }
        }

        String arch = str(meta.get("general.architecture"), "unknown");
        String prefix = arch + ".";
        int blocks = (int) num(meta.get(prefix + "block_count"), blockBytes.isEmpty() ? 0 : maxKey(blockBytes) + 1);
        long embedding = num(meta.get(prefix + "embedding_length"), 0);

        long[] perLayer = new long[blocks];
        for (int i = 0; i < blocks; i++) {
            perLayer[i] = blockBytes.getOrDefault(i, 0L);
        }

        // Tied embeddings have no output.weight, the output reuses the token embedding so it needs to be on the GPU too.
        if (tiedOutput) output += tokenEmbd;

        long[] heads = numbers(meta.get(prefix + "attention.head_count"), blocks, 0);
        long[] headsKv = numbers(meta.get(prefix + "attention.head_count_kv"), blocks, -1);
        boolean kvKnown = embedding > 0 && maxOf(heads) > 0;
        if (maxOf(headsKv) < 0) headsKv = heads; // No GQA info, plain multi head attention.
        long keyLen = num(meta.get(prefix + "attention.key_length"), maxOf(heads) > 0 ? embedding / maxOf(heads) : 0);
        long valLen = num(meta.get(prefix + "attention.value_length"), keyLen);

        long fileType = num(meta.get("general.file_type"), -1);
        String name = str(meta.get("general.name"), file.getFileName().toString());

        return new GgufInfo(file, total, arch, name, FILE_TYPES.getOrDefault(fileType, ""),
                blocks, num(meta.get(prefix + "context_length"), 0), embedding, perLayer,
                input, output, other, headsKv, keyLen, valLen,
                meta.containsKey(prefix + "expert_count") && num(meta.get(prefix + "expert_count"), 0) > 0,
                kvKnown);
    }

    // Reads the metadata (only the small useful values) and the tensor table. Returns where the tensor data starts.
    private static long readHeader(Path file, Map<String, Object> meta, List<Tensor> tensors) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 20)) {
            Reader in = new Reader(raw);
            if (in.u32() != 0x46554747L) throw new IOException(file.getFileName() + " is not a GGUF file");
            long version = in.u32();
            if (version < 2) throw new IOException("Unsupported GGUF version " + version);
            long tensorCount = in.u64();
            long kvCount = in.u64();
            if (tensorCount < 0 || tensorCount > 1_000_000 || kvCount < 0 || kvCount > 1_000_000) {
                throw new IOException("Corrupt GGUF header");
            }

            for (long i = 0; i < kvCount; i++) {
                String key = in.string();
                int type = (int) in.u32();
                Object value = readValue(in, type, !key.startsWith("tokenizer."));
                if (value != null) meta.put(key, value);
            }
            for (long i = 0; i < tensorCount; i++) {
                String name = in.string();
                long dims = in.u32();
                in.skip(dims * 8);
                in.u32(); // Tensor type, the size comes from the offsets instead.
                tensors.add(new Tensor(name, in.u64()));
            }

            long alignment = num(meta.get("general.alignment"), 32);
            if (alignment <= 0) alignment = 32;
            return (in.position + alignment - 1) / alignment * alignment;
        }
    }

    // Only returns the value if keep is true and it's small (scalar, short string, short number array).
    private static Object readValue(Reader in, int type, boolean keep) throws IOException {
        switch (type) {
            case 0, 1, 7 -> {
                long v = in.u8();
                return keep ? (Object) v : null;
            }
            case 2, 3 -> {
                in.skip(2);
                return null;
            }
            case 4 -> {
                long v = in.u32();
                return keep ? (Object) v : null;
            }
            case 5 -> {
                long v = (int) in.u32();
                return keep ? (Object) v : null;
            }
            case 6 -> {
                double v = Float.intBitsToFloat((int) in.u32());
                return keep ? (Object) v : null;
            }
            case 8 -> {
                long len = in.u64();
                if (keep && len <= 4096) return new String(in.bytes((int) len), StandardCharsets.UTF_8);
                in.skip(len);
                return null;
            }
            case 10, 11 -> {
                long v = in.u64();
                return keep ? (Object) v : null;
            }
            case 12 -> {
                double v = Double.longBitsToDouble(in.u64());
                return keep ? (Object) v : null;
            }
            case 9 -> {
                int elementType = (int) in.u32();
                long count = in.u64();
                boolean numeric = elementType != 8 && elementType != 9 && elementType != 7;
                if (keep && numeric && count <= 4096) {
                    long[] values = new long[(int) count];
                    for (int i = 0; i < count; i++) {
                        Object v = readValue(in, elementType, true);
                        values[i] = v instanceof Number n ? n.longValue() : 0;
                    }
                    return values;
                }
                int fixed = fixedSize(elementType);
                if (fixed > 0) {
                    in.skip(count * fixed);
                } else {
                    for (long i = 0; i < count; i++) readValue(in, elementType, false);
                }
                return null;
            }
            default -> throw new IOException("Unknown GGUF value type " + type);
        }
    }

    private static int fixedSize(int type) {
        return switch (type) {
            case 0, 1, 7 -> 1;
            case 2, 3 -> 2;
            case 4, 5, 6 -> 4;
            case 10, 11, 12 -> 8;
            default -> 0; // Strings and nested arrays aren't a fixed size.
        };
    }

    private static long num(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof long[] a && a.length > 0) return a[0];
        return fallback;
    }

    private static String str(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    // The file stores either one number for every layer or one per layer.
    private static long[] numbers(Object value, int blocks, long fallback) {
        long[] out = new long[Math.max(blocks, 1)];
        Arrays.fill(out, fallback);
        if (value instanceof Number n) {
            Arrays.fill(out, n.longValue());
        } else if (value instanceof long[] a) {
            for (int i = 0; i < out.length && i < a.length; i++) out[i] = a[i];
        }
        return out;
    }

    private static long maxOf(long[] values) {
        long max = Long.MIN_VALUE;
        for (long v : values) max = Math.max(max, v);
        return max;
    }

    private static int maxKey(Map<Integer, Long> map) {
        return map.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
    }

    // Little endian reads that keep track of the position in the file.
    private static final class Reader {
        private final InputStream in;
        private final byte[] scratch = new byte[8];
        private final ByteBuffer buffer = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN);
        long position;

        Reader(InputStream in) {
            this.in = in;
        }

        int u8() throws IOException {
            int b = in.read();
            if (b < 0) throw new IOException("Unexpected end of file");
            position++;
            return b;
        }

        long u32() throws IOException {
            fill(4);
            return buffer.getInt(0) & 0xFFFFFFFFL;
        }

        long u64() throws IOException {
            fill(8);
            return buffer.getLong(0);
        }

        String string() throws IOException {
            long len = u64();
            if (len < 0 || len > (1 << 20)) throw new IOException("Corrupt GGUF string length");
            return new String(bytes((int) len), StandardCharsets.UTF_8);
        }

        byte[] bytes(int n) throws IOException {
            byte[] data = in.readNBytes(n);
            if (data.length < n) throw new IOException("Unexpected end of file");
            position += n;
            return data;
        }

        void skip(long n) throws IOException {
            if (n < 0) throw new IOException("Corrupt GGUF length");
            in.skipNBytes(n);
            position += n;
        }

        private void fill(int n) throws IOException {
            int read = in.readNBytes(scratch, 0, n);
            if (read < n) throw new IOException("Unexpected end of file");
            position += n;
        }
    }
}
