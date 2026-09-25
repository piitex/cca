package me.piitex.cca.backend.model;

import me.piitex.cca.App;
import me.piitex.cca.config.ModelSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

// Every .gguf in the models folder. Local setup saves its test model here too.
public final class ModelLibrary {

    // bytes is the total size, all shards added up for a split model
    public record Entry(Path file, long bytes) {
        public String fileName() {
            return file.getFileName().toString();
        }
    }

    private ModelLibrary() {
    }

    public static Path directory() {
        return App.environment.getDataPath("models");
    }

    // mmproj files can't be loaded as a chat model, and only the first shard of a split model is listed.
    public static List<Entry> list() {
        Path models = directory();
        if (!Files.isDirectory(models)) return List.of();
        try (Stream<Path> files = Files.list(models)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .filter(p -> !p.getFileName().toString().toLowerCase(Locale.ROOT).contains("mmproj"))
                    .filter(p -> !GgufInfo.isSecondaryShard(p))
                    .sorted()
                    .map(p -> new Entry(p, GgufInfo.totalBytes(p)))
                    .toList();
        } catch (IOException e) {
            App.logger.error("Could not list models!", e);
            return List.of();
        }
    }

    public static String formatSize(long bytes) {
        double gb = bytes / 1_073_741_824.0;
        if (gb >= 1) return String.format(Locale.ROOT, "%.1f GB", gb);
        return Math.round(bytes / 1_048_576.0) + " MB";
    }

    // The model picked in settings if it still exists, otherwise the first one, otherwise null.
    public static Path resolve(ModelSettings settings) {
        String chosen = settings.modelFile.get();
        if (chosen != null && !chosen.isBlank()) {
            Path path = Path.of(chosen);
            if (!path.isAbsolute()) path = directory().resolve(chosen);
            return Files.isRegularFile(path) ? path : null;
        }
        List<Entry> models = list();
        return models.isEmpty() ? null : models.getFirst().file();
    }
}
