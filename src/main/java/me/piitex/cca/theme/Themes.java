package me.piitex.cca.theme;

import me.piitex.cca.App;
import me.piitex.cca.background.Backgrounds;
import me.piitex.engine.config.Config;
import me.piitex.engine.io.AppEnvironment;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.theme.CrimsonTheme;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.LightTheme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The built in presets, the user's custom themes and which one is on screen.
 * <p>
 * There is only ever one live theme object ({@link #current()}). The engine and every menu hold on to it,
 * so applying a theme copies the colors into it instead of swapping the object. Custom themes are saved
 * one per file in data/themes/[id].conf
 */
public final class Themes {
    public static final String DEFAULT_ID = "violet";

    private static final String FOLDER = "data/themes/";

    private static final Color VIOLET = new Color(0.56f, 0.45f, 0.98f, 1f);
    private static final Color VIOLET_END = new Color(0.32f, 0.78f, 0.98f, 1f);
    private static final Color CRIMSON = new Color(0.86f, 0.08f, 0.24f, 1f);
    private static final Color CRIMSON_END = new Color(0.95f, 0.22f, 0.28f, 1f);

    private static final List<AppTheme> PRESETS = List.of(
            AppTheme.of(DEFAULT_ID, "Violet", new DarkTheme(), VIOLET, VIOLET_END),
            AppTheme.of("light", "Light", new LightTheme(), VIOLET, VIOLET_END),
            AppTheme.of("crimson", "Crimson", new CrimsonTheme(), CRIMSON, CRIMSON_END));

    private static final AppTheme LIVE = PRESETS.getFirst().copy();
    private static final List<AppTheme> CUSTOM = new ArrayList<>();
    private static String currentId = DEFAULT_ID;

    private Themes() {
    }

    public static AppTheme current() {
        return LIVE;
    }

    public static String currentId() {
        return currentId;
    }

    public static List<AppTheme> presets() {
        return PRESETS;
    }

    // A snapshot, edit a copy then call saveCustom.
    public static List<AppTheme> custom() {
        return List.copyOf(CUSTOM);
    }

    // Presets first.
    public static List<AppTheme> all() {
        List<AppTheme> all = new ArrayList<>(PRESETS);
        all.addAll(CUSTOM);
        return all;
    }

    public static AppTheme find(String id) {
        return all().stream().filter(theme -> theme.id().equals(id)).findAny().orElse(null);
    }

    // Unknown ids fall back to the default. Screens that read colors while building still need a redraw.
    public static void apply(String id) {
        AppTheme theme = find(id);
        if (theme == null) theme = PRESETS.getFirst();
        currentId = theme.id();
        apply(theme);
    }

    // Shows a theme without selecting it, used for previews.
    public static void apply(AppTheme theme) {
        LIVE.takeLookFrom(theme);
        ThemeManager.setCurrent(LIVE);
    }

    public static void load(AppEnvironment environment) {
        CUSTOM.clear();
        Path folder = environment.getDataPath(FOLDER);
        if (!Files.isDirectory(folder)) return;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".conf")).toList()) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - ".conf".length());
                try {
                    CUSTOM.add(AppTheme.readFrom(Config.load(file), id, PRESETS.getFirst()));
                } catch (IOException | RuntimeException e) {
                    // Skip a broken file rather than losing every theme.
                    App.logger.warn("Could not read theme {}", file, e);
                }
            }
        } catch (IOException e) {
            App.logger.warn("Could not list the themes folder!", e);
        }
        CUSTOM.sort(Comparator.comparing(t -> t.name().toLowerCase(Locale.ROOT)));
        App.logger.info("Loaded {} custom themes.", CUSTOM.size());
    }

    // The id doubles as the file name, so only lowercase letters, numbers and dashes.
    public static String newCustomId(String name) {
        return newCustomId(name, id -> find(id) != null);
    }

    // Same as above but the caller can mark extra ids as taken (themes made but not saved yet).
    public static String newCustomId(String name, Predicate<String> taken) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "theme";
        String base = "custom-" + slug;
        String id = base;
        for (int n = 2; taken.test(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    // Adds the theme, or replaces the one with the same id.
    public static void saveCustom(AppEnvironment environment, AppTheme theme) {
        if (theme.isBuiltIn()) return;
        AppTheme stored = theme.copy();
        CUSTOM.removeIf(t -> t.id().equals(stored.id()));
        CUSTOM.add(stored);
        CUSTOM.sort(Comparator.comparing(t -> t.name().toLowerCase(Locale.ROOT)));
        try {
            Config config = new Config();
            stored.writeTo(config);
            environment.saveConfig(FOLDER + stored.id() + ".conf", config);
        } catch (IOException e) {
            App.logger.error("Could not save theme {}", stored.id(), e);
        }
    }

    public static void deleteCustom(AppEnvironment environment, String id) {
        CUSTOM.removeIf(t -> t.id().equals(id));
        try {
            Files.deleteIfExists(environment.getDataPath(FOLDER + id + ".conf"));
            Backgrounds.deleteThemeImage(id);
        } catch (IOException e) {
            App.logger.error("Could not delete theme {}", id, e);
        }
    }
}
