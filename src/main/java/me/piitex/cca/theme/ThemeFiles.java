package me.piitex.cca.theme;

import me.piitex.cca.background.Backgrounds;
import me.piitex.engine.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Themes as single files people can share. A .cctheme is a zip with the theme's config and, when the
 * theme brings a background picture, the picture.
 * <p>
 * Nothing in an imported file is trusted: the zip is only read by its two fixed entry names (never
 * unpacked by the names inside), and the picture path in the config is replaced with the file that was read.
 */
public final class ThemeFiles {
    public static final String EXTENSION = "cctheme";

    private static final String CONFIG_ENTRY = "theme.conf";
    private static final Set<String> IMAGE_TYPES = Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp");
    private static final long MAX_CONFIG_BYTES = 1L << 20;
    private static final long MAX_IMAGE_BYTES = 64L << 20;
    private static final int MAX_NAME_LENGTH = 40;

    private ThemeFiles() {
    }

    public static void export(AppTheme theme, Path target) throws IOException {
        Config config = new Config();
        theme.writeTo(config);

        String imageEntry = null;
        Path image = null;
        if (theme.background() != null) {
            image = Backgrounds.imageFile(String.valueOf(theme.background().getOrDefault(Backgrounds.IMAGE_PATH, "")));
            if (image != null && Files.isRegularFile(image) && IMAGE_TYPES.contains(extension(image))) {
                imageEntry = "image." + extension(image);
            } else {
                image = null;
            }
            config.set(Backgrounds.IMAGE_PATH, imageEntry == null ? "" : imageEntry);
        }

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            zip.putNextEntry(new ZipEntry(CONFIG_ENTRY));
            zip.write(config.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (image != null) {
                zip.putNextEntry(new ZipEntry(imageEntry));
                Files.copy(image, zip);
                zip.closeEntry();
            }
        }
    }

    /**
     * Reads a theme from a .cctheme. The result is a new custom theme, with its background picture (if any)
     * unpacked to a temporary file that the settings screen copies into the data folder on save.
     *
     * @param idFor picks the id for a theme name, so it can't clash with the themes already there
     */
    public static AppTheme read(Path file, Function<String, String> idFor) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry(CONFIG_ENTRY);
            if (entry == null) throw new IOException("There is no theme in " + file.getFileName());
            Config config = Config.parse(new String(readLimited(zip, entry, MAX_CONFIG_BYTES), StandardCharsets.UTF_8));

            String name = config.getString("name", "").trim();
            if (name.isEmpty()) name = "Imported theme";
            if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH).trim();
            AppTheme theme = AppTheme.readFrom(config, idFor.apply(name), Themes.presets().getFirst());
            theme.setName(name);

            if (theme.background() != null) {
                Map<String, Object> background = new LinkedHashMap<>(theme.background());
                background.replaceAll((key, value) -> sane(value));
                background.put(Backgrounds.IMAGE_PATH, unpackImage(zip, String.valueOf(background.get(Backgrounds.IMAGE_PATH))));
                theme.setBackground(background);
            }
            return theme;
        }
    }

    // A count of a million points would freeze the app, so numbers are kept to a range the settings screen allows or near it.
    private static Object sane(Object value) {
        if (value instanceof Integer i) return Math.max(0, Math.min(1000, i));
        if (value instanceof Double d) return Double.isFinite(d) ? Math.max(-100_000, Math.min(100_000, d)) : 0.0;
        return value;
    }

    // Only a file named exactly like an export names it (image.png and so on), anything else is no picture.
    private static String unpackImage(ZipFile zip, String entryName) throws IOException {
        if (!entryName.startsWith("image.") || !IMAGE_TYPES.contains(entryName.substring("image.".length()))) return "";
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) return "";
        byte[] bytes = readLimited(zip, entry, MAX_IMAGE_BYTES);
        Path folder = Files.createTempDirectory("cca-theme");
        Path picture = folder.resolve(entryName);
        Files.write(picture, bytes);
        // The folder is registered first so it's removed last.
        folder.toFile().deleteOnExit();
        picture.toFile().deleteOnExit();
        return picture.toString();
    }

    private static byte[] readLimited(ZipFile zip, ZipEntry entry, long limit) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes((int) limit + 1);
            if (bytes.length > limit) throw new IOException(entry.getName() + " is too large to be part of a theme");
            return bytes;
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
