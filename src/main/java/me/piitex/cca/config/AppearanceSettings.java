package me.piitex.cca.config;

import me.piitex.cca.App;
import me.piitex.cca.background.Backgrounds;
import me.piitex.cca.theme.Themes;
import me.piitex.engine.config.Config;
import me.piitex.engine.io.AppEnvironment;

import java.io.IOException;
import java.util.Map;

/**
 * How the app looks, saved to data/appearance.conf. Custom themes are their own files under data/themes/,
 * this only remembers which one is selected. Works the same way as ModelSettings: the Settings screen
 * edits a {@link #copy()} and applies it on save.
 */
public final class AppearanceSettings extends Settings {

    // The selected preset or custom theme, see Themes.
    public final Setting<String> themeId = str("theme.id", Themes.DEFAULT_ID);

    // constellation, dots, electric, gradient, solid or image. Every type keeps its own options so
    // switching back finds them how they were left. Colors are #RRGGBBAA.
    public final Setting<String> backgroundType = str("background.type", Backgrounds.Type.CONSTELLATION.id());

    // These defaults are the look the app originally shipped with.
    public final Setting<Integer> constellationCount = num("background.constellation.count", 300);
    public final Setting<Double> constellationSize = dec("background.constellation.size", 2.0);
    public final Setting<Double> constellationMinSpeed = dec("background.constellation.minSpeed", 10.0);
    public final Setting<Double> constellationMaxSpeed = dec("background.constellation.maxSpeed", 40.0);
    public final Setting<String> constellationParticleColor = str("background.constellation.particleColor", "#FFFFFFCC");
    public final Setting<Boolean> constellationRainbow = bool("background.constellation.rainbow", true);
    public final Setting<Double> constellationRainbowSeconds = dec("background.constellation.rainbowSeconds", 6.0);
    public final Setting<String> constellationLineColor = str("background.constellation.lineColor", "#FFFFFF59");
    public final Setting<Double> constellationLinkDistance = dec("background.constellation.linkDistance", 120.0);
    public final Setting<Double> constellationLineThickness = dec("background.constellation.lineThickness", 1.0);
    public final Setting<Boolean> constellationMouse = bool("background.constellation.mouse", true);
    public final Setting<Double> constellationRepelRadius = dec("background.constellation.repelRadius", 120.0);
    public final Setting<Double> constellationRepelStrength = dec("background.constellation.repelStrength", 600.0);
    public final Setting<Double> constellationOpacity = dec("background.constellation.opacity", 1.0);

    // Toned way down from the engine defaults so it doesn't distract from a long chat.
    public final Setting<Double> dotsSpacing = dec("background.dots.spacing", 38.0);
    public final Setting<Double> dotsMinSize = dec("background.dots.minSize", 1.0);
    public final Setting<Double> dotsMaxSize = dec("background.dots.maxSize", 1.6);
    public final Setting<Double> dotsWaveLength = dec("background.dots.waveLength", 260.0);
    public final Setting<Double> dotsSpeed = dec("background.dots.speed", 0.35);
    public final Setting<Double> dotsIntensity = dec("background.dots.intensity", 1.0);
    public final Setting<Double> dotsHueSpan = dec("background.dots.hueSpan", 0.6);
    public final Setting<Double> dotsSaturation = dec("background.dots.saturation", 0.85);
    public final Setting<Double> dotsBrightness = dec("background.dots.brightness", 1.0);
    public final Setting<Double> dotsOpacity = dec("background.dots.opacity", 0.22);

    public final Setting<Integer> electricNodes = num("background.electric.nodes", 100);
    public final Setting<Double> electricNodeSize = dec("background.electric.nodeSize", 1);
    public final Setting<Double> electricMinSpeed = dec("background.electric.minSpeed", 60.0);
    public final Setting<Double> electricMaxSpeed = dec("background.electric.maxSpeed", 140.0);
    public final Setting<Double> electricLinkDistance = dec("background.electric.linkDistance", 260.0);
    public final Setting<Double> electricArcRate = dec("background.electric.arcRate", 5.0);
    public final Setting<Double> electricMinArc = dec("background.electric.minArc", 70.0);
    public final Setting<Double> electricMaxArc = dec("background.electric.maxArc", 260.0);
    public final Setting<Double> electricArcLifetime = dec("background.electric.arcLifetime", 0.28);
    public final Setting<Double> electricThickness = dec("background.electric.thickness", 0.5);
    public final Setting<Double> electricJaggedness = dec("background.electric.jaggedness", 1.0);
    public final Setting<Double> electricBranchChance = dec("background.electric.branchChance", 0.6);
    public final Setting<Boolean> electricRainbow = bool("background.electric.rainbow", false);
    public final Setting<String> electricGlowColor = str("background.electric.glowColor", "#59B3FFFF");
    public final Setting<String> electricCoreColor = str("background.electric.coreColor", "#EBF7FFFF");
    public final Setting<Boolean> electricSparks = bool("background.electric.sparks", true);
    public final Setting<Boolean> electricMouse = bool("background.electric.mouse", false);
    public final Setting<Double> electricStrikeRadius = dec("background.electric.strikeRadius", 220.0);
    public final Setting<Double> electricOpacity = dec("background.electric.opacity", 1.0);

    public final Setting<String> gradientStart = str("background.gradient.start", "#2A1B5EFF");
    public final Setting<String> gradientEnd = str("background.gradient.end", "#0B0A1AFF");
    public final Setting<Boolean> gradientScrolling = bool("background.gradient.scrolling", true);
    public final Setting<Double> gradientSpeed = dec("background.gradient.speed", 25.0);

    public final ImageOptions backgroundImage = image("background.image");
    public final Setting<Boolean> imageBackdropFollowTheme = bool("background.image.backdropFollowTheme", true);
    public final Setting<String> imageBackdrop = str("background.image.backdrop", "#000000FF");

    public final Setting<Boolean> solidFollowTheme = bool("background.solid.followTheme", true);
    public final Setting<String> solidColor = str("background.solid.color", "#14141EFF");

    // The chat's own options, see ui.chat.
    public final Setting<String> chatStyle = str("chat.style", "text");
    public final ImageOptions chatImage = image("chat.window.image");
    public final Setting<String> dialogueColor = str("chat.roleplay.dialogueColor", "#FFFFE0FF");
    public final Setting<String> actionColor = str("chat.roleplay.actionColor", "#ADD8E6FF");
    public final Setting<Boolean> avatarShow = bool("chat.avatar.show", true);
    public final Setting<Boolean> avatarShowUser = bool("chat.avatar.showUser", true);
    public final Setting<String> avatarShape = str("chat.avatar.shape", "circle");
    // Only the layout half is used, the picture is the character's own.
    public final ImageOptions avatarImage = image("chat.avatar.image");
    public final Setting<Integer> avatarWidth = num("chat.avatar.width", 34);
    public final Setting<Integer> avatarHeight = num("chat.avatar.height", 34);
    public final Setting<Integer> portraitWidth = num("chat.avatar.portraitWidth", 132);
    public final Setting<Integer> portraitHeight = num("chat.avatar.portraitHeight", 132);

    // The character hub, see ui.CharacterCards and ui.ItemHub.
    public final Setting<Integer> cardWidth = num("characters.card.width", 190);
    public final Setting<Integer> cardGap = num("characters.card.gap", 18);
    public final Setting<Integer> cardRadius = num("characters.card.radius", 18);
    public final Setting<Boolean> cardButtons = bool("characters.card.buttons", true);
    public final Setting<Boolean> folderPortraits = bool("characters.folder.portraits", true);
    public final Setting<Integer> popoutColumns = num("characters.popout.columns", 4);
    public final Setting<Double> popoutOpacity = dec("characters.popout.opacity", 0.55);

    // The window, see App.applyWindowSettings. Without vsync the frame rate is capped at maxFps.
    public final Setting<Boolean> vsync = bool("general.vsync", true);
    public final Setting<Integer> maxFps = num("general.maxFps", 60);

    private AppearanceSettings() {
    }

    // A path is inside the data folder once saved. While it's being picked it can point anywhere.
    private ImageOptions image(String prefix) {
        return new ImageOptions(str(prefix + ".path", ""), str(prefix + ".fit", "cover"), dec(prefix + ".zoom", 100.0),
                dec(prefix + ".alignX", 50.0), dec(prefix + ".alignY", 50.0), dec(prefix + ".opacity", 1.0), str(prefix + ".overlay", "#00000000"));
    }

    public static AppearanceSettings load(AppEnvironment environment, Config legacy) throws IOException {
        AppearanceSettings settings = new AppearanceSettings();
        boolean existed = environment.hasSavedConfig("data/appearance.conf");
        settings.config = environment.loadOrCreateConfig("data/appearance.conf", c -> {
        });

        // Older installs picked dark or light in app.conf. Dark was the violet theme.
        if (!existed && legacy != null) {
            String old = legacy.getString("theme", "dark");
            settings.themeId.set("light".equalsIgnoreCase(old) ? "light" : Themes.DEFAULT_ID);
        }
        settings.readValues(settings.config);
        if (legacy != null && !settings.config.has(settings.chatStyle.key())) {
            settings.chatStyle.set(legacy.getString("chatStyle", "text"));
        }
        if (!existed) settings.save();
        return settings;
    }

    public AppearanceSettings copy() {
        AppearanceSettings copy = new AppearanceSettings();
        copy.loadFrom(this);
        return copy;
    }

    public void apply(AppearanceSettings edited) {
        loadFrom(edited);
        save();
    }

    public void loadFrom(AppearanceSettings other) {
        copyValuesFrom(other);
    }

    public boolean differsFrom(AppearanceSettings other) {
        return valuesDifferFrom(other);
    }

    // Every background.* option, which is what a theme keeps when it brings its own background.
    public Map<String, Object> backgroundValues() {
        return valuesWithPrefix("background.");
    }

    public void loadBackgroundValues(Map<String, Object> values) {
        putValues(values);
    }

    // The background options in a theme's file. Missing ones are the defaults.
    public static Map<String, Object> readBackground(Config source) {
        AppearanceSettings settings = new AppearanceSettings();
        settings.readValues(source);
        return settings.backgroundValues();
    }

    public void resetGroup(String... prefixes) {
        resetValues(prefixes);
    }

    public void resetAll() {
        resetValues();
    }

    public void save() {
        if (config == null) return;
        writeValues(config);
        try {
            config.save();
        } catch (IOException e) {
            App.logger.error("Could not save appearance.conf!", e);
        }
    }
}
