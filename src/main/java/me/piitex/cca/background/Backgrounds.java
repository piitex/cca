package me.piitex.cca.background;

import me.piitex.cca.App;
import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.config.ImageOptions;
import me.piitex.cca.config.Settings.Setting;
import me.piitex.cca.theme.AppTheme;
import me.piitex.cca.theme.Themes;
import me.piitex.engine.Window;
import me.piitex.engine.ui.background.AnimatedDotBackground;
import me.piitex.engine.ui.background.Background;
import me.piitex.engine.ui.background.ConstellationBackground;
import me.piitex.engine.ui.background.ElectricNodeBackground;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.RainbowColor;
import me.piitex.engine.ui.color.ScrollingGradient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Stream;

/**
 * Turns the background settings into what the engine draws. A background is an effect (constellation,
 * dots, electric, image) drawn over a fill. Gradient and solid are only a fill.
 * The window and the preview in Settings both go through here so they always match.
 */
public final class Backgrounds {
    // Changing the particle count or speed restarts the simulation, so those are only set when they
    // actually change. Otherwise tweaking a color in the preview would reset it every time.
    private static final Map<Background, String> SEEDED = new WeakHashMap<>();

    public static final String IMAGE_PATH = "background.image.path";

    private Backgrounds() {
    }

    public enum Type {
        CONSTELLATION("constellation", "Constellation", "Drifting points joined by lines when they come close."),
        DOTS("dots", "Animated dots", "A grid of dots that swell and shift color in slow waves."),
        ELECTRIC("electric", "Electric", "Glowing orbs that arc lightning between one another."),
        GRADIENT("gradient", "Gradient", "Two colors blended across the window, static or slowly scrolling."),
        SOLID("solid", "Solid color", "One flat color, or the theme's own window color."),
        IMAGE("image", "Image", "A picture of your own, with size, position, opacity and a dimming overlay.");

        private final String id;
        private final String label;
        private final String blurb;

        Type(String id, String label, String blurb) {
            this.id = id;
            this.label = label;
            this.blurb = blurb;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public String blurb() {
            return blurb;
        }

        // Falls back to constellation, the original background.
        public static Type of(String id) {
            for (Type type : values()) {
                if (type.id.equals(id)) return type;
            }
            return CONSTELLATION;
        }
    }

    // The selected theme's own background when it brings one, otherwise the settings as they are.
    public static AppearanceSettings effective(AppearanceSettings settings) {
        AppTheme theme = Themes.find(settings.themeId.get());
        if (theme == null || theme.background() == null) return settings;
        AppearanceSettings themed = settings.copy();
        themed.loadBackgroundValues(theme.background());
        return themed;
    }

    public static void apply(Window window, AppearanceSettings chosen) {
        AppearanceSettings settings = effective(chosen);
        Type type = Type.of(settings.backgroundType.get());
        Background effect = createEffect(type);
        if (effect != null) configure(effect, settings, 1.0);
        window.setBackground(effect);

        Paint fill = fill(settings);
        if (fill == null) {
            window.followThemeBackground();
        } else {
            window.setBackgroundColor(fill);
        }
    }

    // Null for the types that are only a fill.
    public static Background createEffect(Type type) {
        return switch (type) {
            case CONSTELLATION -> new ConstellationBackground();
            case DOTS -> new AnimatedDotBackground();
            case ELECTRIC -> new ElectricNodeBackground();
            case IMAGE -> new ImageBackground();
            case GRADIENT, SOLID -> null;
        };
    }

    // The fill under the effect. Null uses the theme's window color.
    public static Paint fill(AppearanceSettings s) {
        return switch (Type.of(s.backgroundType.get())) {
            case GRADIENT -> {
                Color start = color(s.gradientStart);
                Color end = color(s.gradientEnd);
                yield s.gradientScrolling.get()
                        ? new ScrollingGradient(start, end, s.gradientSpeed.get().floatValue())
                        : new LinearGradient(start, end);
            }
            case SOLID -> s.solidFollowTheme.get() ? null : color(s.solidColor);
            case IMAGE -> s.imageBackdropFollowTheme.get() ? null : color(s.imageBackdrop);
            default -> null;
        };
    }

    /**
     * Sets every option on the effect.
     *
     * @param areaScale how big the effect is compared to the window. The preview uses this to keep the
     *                  same density of particles instead of the same count. 1 for the window.
     */
    public static void configure(Background effect, AppearanceSettings s, double areaScale) {
        switch (effect) {
            case ConstellationBackground c -> configure(c, s, areaScale);
            case AnimatedDotBackground d -> configure(d, s);
            case ElectricNodeBackground e -> configure(e, s, areaScale);
            case ImageBackground i -> configure(i, s.backgroundImage);
            default -> {
            }
        }
    }

    private static void configure(ConstellationBackground c, AppearanceSettings s, double areaScale) {
        int count = scaled(s.constellationCount.get(), areaScale);
        float minSpeed = s.constellationMinSpeed.get().floatValue();
        float maxSpeed = s.constellationMaxSpeed.get().floatValue();
        if (reseed(c, count + "/" + minSpeed + "/" + maxSpeed)) {
            c.setParticleCount(count);
            c.setSpeedRange(minSpeed, maxSpeed);
        }
        c.setParticleRadius(s.constellationSize.get().floatValue());
        c.setParticleColor(color(s.constellationParticleColor));
        c.setLineColor(s.constellationRainbow.get()
                ? new RainbowColor(s.constellationRainbowSeconds.get().floatValue())
                : color(s.constellationLineColor));
        c.setLinkDistance(s.constellationLinkDistance.get().floatValue());
        c.setLineThickness(s.constellationLineThickness.get().floatValue());
        c.setMouseInteractionEnabled(s.constellationMouse.get());
        c.setRepelRadius(s.constellationRepelRadius.get().floatValue());
        c.setRepelStrength(s.constellationRepelStrength.get().floatValue());
        c.setOpacity(s.constellationOpacity.get().floatValue());
    }

    /**
     * Sets the picture, fit and look of {@code i} from {@code o}.
     */
    public static void configure(ImageBackground i, ImageOptions o) {
        configure(i, o, imageFile(o.path().get()));
    }

    // Same, with the picture given instead of read from the options, like a character's icon.
    public static void configure(ImageBackground i, ImageOptions o, Path file) {
        i.setSource(file);
        i.setFit(ImageBackground.Fit.of(o.fit().get()));
        i.setZoom(o.zoom().get().floatValue() / 100f);
        i.setAlignment(o.alignX().get().floatValue() / 100f, o.alignY().get().floatValue() / 100f);
        i.setOverlay(color(o.overlay()));
        i.setOpacity(o.opacity().get().floatValue());
    }

    private static void configure(AnimatedDotBackground d, AppearanceSettings s) {
        float minSize = s.dotsMinSize.get().floatValue();
        float maxSize = s.dotsMaxSize.get().floatValue();
        d.setSpacing(s.dotsSpacing.get().floatValue());
        d.setMinRadius(Math.min(minSize, maxSize));
        d.setMaxRadius(Math.max(minSize, maxSize));
        d.setWaveLength(s.dotsWaveLength.get().floatValue());
        d.setSpeed(s.dotsSpeed.get().floatValue());
        d.setIntensity(s.dotsIntensity.get().floatValue());
        d.setHueSpan(s.dotsHueSpan.get().floatValue());
        d.setSaturation(s.dotsSaturation.get().floatValue());
        d.setBrightness(s.dotsBrightness.get().floatValue());
        d.setAlpha(s.dotsOpacity.get().floatValue());
    }

    private static void configure(ElectricNodeBackground e, AppearanceSettings s, double areaScale) {
        int nodes = Math.max(s.electricNodes.get() == 0 ? 0 : 1, scaled(s.electricNodes.get(), areaScale));
        float minSpeed = s.electricMinSpeed.get().floatValue();
        float maxSpeed = s.electricMaxSpeed.get().floatValue();
        if (reseed(e, nodes + "/" + minSpeed + "/" + maxSpeed)) {
            e.setNodeCount(nodes);
            e.setSpeedRange(minSpeed, maxSpeed);
        }
        e.setNodeRadius(s.electricNodeSize.get().floatValue());
        e.setNodeLinkDistance(s.electricLinkDistance.get().floatValue());
        e.setArcRate(s.electricArcRate.get().floatValue());
        e.setArcLengthRange(s.electricMinArc.get().floatValue(), s.electricMaxArc.get().floatValue());
        e.setArcLifetime(s.electricArcLifetime.get().floatValue());
        e.setBoltThickness(s.electricThickness.get().floatValue());
        e.setJaggedness(s.electricJaggedness.get().floatValue());
        e.setBranchChance(s.electricBranchChance.get().floatValue());
        e.setGlowColor(s.electricRainbow.get() ? new RainbowColor() : color(s.electricGlowColor));
        e.setCoreColor(color(s.electricCoreColor));
        e.setSparksEnabled(s.electricSparks.get());
        e.setMouseInteractionEnabled(s.electricMouse.get());
        e.setStrikeRadius(s.electricStrikeRadius.get().floatValue());
        e.setOpacity(s.electricOpacity.get().floatValue());
    }

    // Relative paths are inside the data folder. Absolute ones are a picture that was just picked and not saved yet.
    public static Path imageFile(String path) {
        if (path == null || path.isBlank()) return null;
        Path p = Path.of(path);
        return p.isAbsolute() ? p : App.environment.getDataPath(path);
    }

    // Called on save.
    public static void storeImage(AppearanceSettings s) {
        s.backgroundImage.path().set(store(s.backgroundImage.path().get(), "backgrounds"));
        s.chatImage.path().set(store(s.chatImage.path().get(), "chat"));
    }

    // A theme that brings a background keeps its picture in its own folder, so themes can't clear each other's.
    public static void storeThemeImage(AppTheme theme) {
        Map<String, Object> background = theme.background();
        if (background == null) {
            deleteThemeImage(theme.id());
            return;
        }
        Map<String, Object> stored = new LinkedHashMap<>(background);
        stored.put(IMAGE_PATH, store(String.valueOf(background.getOrDefault(IMAGE_PATH, "")), "theme-backgrounds", theme.id()));
        theme.setBackground(stored);
    }

    public static void deleteThemeImage(String themeId) {
        try {
            Path directory = App.environment.getDataPath("data/theme-backgrounds/" + themeId);
            if (!Files.isDirectory(directory)) return;
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            App.logger.error("Could not remove the background picture of theme {}", themeId, e);
        }
    }

    // Copies a picked picture into data/<folder> so it still works if the original is moved, removes old
    // copies nothing uses anymore, and returns the path to save.
    private static String store(String current, String... folder) {
        String saved = current;
        try {
            Path directory = App.environment.getDirectory("data", folder);
            Path source = imageFile(current);
            String keep = null;
            if (source != null && Files.isRegularFile(source)) {
                if (source.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) {
                    keep = source.getFileName().toString();
                } else {
                    keep = source.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
                    Files.copy(source, directory.resolve(keep), StandardCopyOption.REPLACE_EXISTING);
                    App.logger.info("Copied {} image '{}'", String.join("/", folder), keep);
                }
                saved = "data/" + String.join("/", folder) + "/" + keep;
            }
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    if (!file.getFileName().toString().equals(keep)) Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            App.logger.error("Could not store the {} image!", String.join("/", folder), e);
        }
        return saved;
    }

    // A smaller area keeps about the same density, but never drops below a handful.
    private static int scaled(int count, double areaScale) {
        if (areaScale >= 1.0 || count <= 0) return count;
        return (int) Math.max(Math.min(count, 12), Math.round(count * areaScale));
    }

    private static boolean reseed(Background effect, String signature) {
        return !signature.equals(SEEDED.put(effect, signature));
    }

    private static Color color(Setting<String> setting) {
        Color parsed = AppTheme.parseHex(setting.get());
        if (parsed == null) parsed = AppTheme.parseHex(setting.getDefault());
        return parsed == null ? Color.WHITE : parsed;
    }
}
