package me.piitex.cca.ui.settings;

import me.piitex.cca.background.Backgrounds.Type;
import me.piitex.cca.background.ImageBackground;
import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.config.Settings.Setting;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.SpinnerOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;

// The Background tab. A live preview on top and the options for the selected type under it.
// Numbers and colors only update the preview (a redraw would drop a drag), toggles and the type
// dropdown change which rows exist so those redraw the whole screen.
final class BackgroundTab {
    private static final double GAP = 16;

    private final Supplier<Color> themeWindow;
    private final Consumer<SettingsForm> themeLink;
    private final ScrollMemory scroll;
    private final Runnable changed;
    private final Runnable redraw;

    // The settings being edited, the draft or the selected theme's own background. Set by build.
    private AppearanceSettings draft;
    private BackgroundPreview preview;

    /**
     * @param themeWindow the window color of the theme that's selected, shown under the effect in the preview
     * @param themeLink   adds the rows that tie the background to the selected theme
     * @param changed     called after any edit to refresh the footer
     * @param redraw      rebuilds the screen
     */
    BackgroundTab(Supplier<Color> themeWindow, Consumer<SettingsForm> themeLink, ScrollMemory scroll, Runnable changed, Runnable redraw) {
        this.themeWindow = themeWindow;
        this.themeLink = themeLink;
        this.scroll = scroll;
        this.changed = changed;
        this.redraw = redraw;
    }

    Element build(AppearanceSettings edited, double width, double height) {
        draft = edited;
        Container holder = Components.transparent(width, height);
        preview = new BackgroundPreview(width);
        preview.show(draft, themeWindow.get());
        preview.element().setPosition(0, 0);
        holder.addElement(preview.element());

        SettingsForm f = new SettingsForm(width, Math.max(0, height - BackgroundPreview.HEIGHT - GAP), scroll);
        Type type = Type.of(draft.backgroundType.get());
        f.note("The preview is scaled down and runs the real effect. Save & Apply puts it behind the whole app.");
        themeLink.accept(f);
        f.section("Background");
        f.row("Style", type.blurb(), SettingsUi.choice(draft.backgroundType,
                Arrays.stream(Type.values()).map(Type::id).toList(), id -> Type.of(id).label(), this::restructured));
        f.endCard();

        switch (type) {
            case CONSTELLATION -> constellation(f);
            case DOTS -> dots(f);
            case ELECTRIC -> electric(f);
            case GRADIENT -> gradient(f);
            case SOLID -> solid(f);
            case IMAGE -> image(f);
        }
        Element form = f.finish();
        form.setPosition(0, BackgroundPreview.HEIGHT + GAP);
        holder.addElement(form);
        return holder;
    }

    private void constellation(SettingsForm f) {
        AppearanceSettings s = draft;
        f.section("Points");
        f.row("Count", "How many points drift around. More points cost more to draw.", whole(s.constellationCount, 0, 1000, 10));
        f.row("Size", "The radius of each point.", number(s.constellationSize, 0.5, 8, 0.5, 1));
        f.row("Slowest speed", "The slowest a point drifts.", number(s.constellationMinSpeed, 0, 200, 5, 0));
        f.row("Fastest speed", "The fastest a point drifts.", number(s.constellationMaxSpeed, 0, 400, 5, 0));
        f.row("Color", "The color of the points.", color(s.constellationParticleColor, true));

        f.section("Lines");
        f.row("Rainbow lines", "Cycle the lines through every color instead of using one.", flag(s.constellationRainbow, true));
        if (s.constellationRainbow.get()) {
            f.row("Rainbow speed", "Seconds for the lines to go around the whole color wheel.", number(s.constellationRainbowSeconds, 1, 60, 1, 0));
        } else {
            f.row("Line color", "The color of the lines between points.", color(s.constellationLineColor, true));
        }
        f.row("Link distance", "How close two points must be to be joined.", number(s.constellationLinkDistance, 10, 400, 10, 0));
        f.row("Line thickness", "The width of the lines.", number(s.constellationLineThickness, 0.5, 6, 0.5, 1));

        f.section("Mouse");
        f.row("Push points away", "Points move out of the way of the mouse pointer.", flag(s.constellationMouse, true));
        if (s.constellationMouse.get()) {
            f.row("Push radius", "How close the pointer must be to push a point.", number(s.constellationRepelRadius, 20, 400, 10, 0));
            f.row("Push strength", "How hard a point is pushed.", number(s.constellationRepelStrength, 50, 2000, 50, 0));
        }

        f.section("Overall");
        f.row("Opacity", "Fade the whole effect toward the window color.", number(s.constellationOpacity, 0, 1, 0.05, 2));
        f.endCard();
    }

    private void dots(SettingsForm f) {
        AppearanceSettings s = draft;
        f.section("Grid");
        f.row("Spacing", "The distance between neighboring dots.", number(s.dotsSpacing, 8, 120, 2, 0));
        f.row("Smallest dot", "The radius of a dot at rest.", number(s.dotsMinSize, 0, 12, 0.2, 1));
        f.row("Largest dot", "The radius of a dot at the crest of a wave.", number(s.dotsMaxSize, 0.5, 14, 0.2, 1));

        f.section("Motion");
        f.row("Speed", "How fast the waves travel.", number(s.dotsSpeed, 0.05, 4, 0.05, 2));
        f.row("Wave length", "The distance between the crests of the waves.", number(s.dotsWaveLength, 40, 800, 20, 0));
        f.row("Intensity", "How strongly the waves swell the dots.", number(s.dotsIntensity, 0, 2, 0.1, 1));

        f.section("Color");
        f.row("Hue range", "How much of the color wheel the dots move through. 0 keeps a single color.", number(s.dotsHueSpan, 0, 1, 0.05, 2));
        f.row("Saturation", "How vivid the colors are.", number(s.dotsSaturation, 0, 1, 0.05, 2));
        f.row("Brightness", "How light the colors are.", number(s.dotsBrightness, 0, 1, 0.05, 2));
        f.row("Opacity", "How solid the dots are. Low values keep them in the background.", number(s.dotsOpacity, 0, 1, 0.02, 2));
        f.endCard();
    }

    private void electric(SettingsForm f) {
        AppearanceSettings s = draft;
        f.section("Orbs");
        f.row("Count", "How many orbs bounce around.", whole(s.electricNodes, 0, 200, 1));
        f.row("Size", "The radius of an orb's bright core.", number(s.electricNodeSize, 0.1, 30, 1, 1));
        f.row("Slowest speed", "The slowest an orb moves.", number(s.electricMinSpeed, 0, 400, 10, 0));
        f.row("Fastest speed", "The fastest an orb moves.", number(s.electricMaxSpeed, 0, 600, 10, 0));
        f.row("Link distance", "How close two orbs must be to arc to each other.", number(s.electricLinkDistance, 0, 600, 20, 0));

        f.section("Lightning");
        f.row("Arc rate", "New bolts each second, per orb.", number(s.electricArcRate, 0, 30, 0.5, 1));
        f.row("Shortest bolt", "The shortest a bolt reaches.", number(s.electricMinArc, 10, 600, 10, 0));
        f.row("Longest bolt", "The farthest a bolt reaches.", number(s.electricMaxArc, 10, 800, 10, 0));
        f.row("Bolt lifetime", "Seconds a bolt lasts.", number(s.electricArcLifetime, 0.05, 2, 0.05, 2));
        f.row("Thickness", "The width of a bolt.", number(s.electricThickness, 0.5, 8, 0.5, 1));
        f.row("Jaggedness", "How crooked a bolt is.", number(s.electricJaggedness, 0, 3, 0.1, 1));
        f.row("Branching", "The chance a bolt splits into smaller ones.", number(s.electricBranchChance, 0, 1, 0.05, 2));
        f.row("Sparks", "Sparks fly off where bolts land.", flag(s.electricSparks, false));

        f.section("Color");
        f.row("Rainbow glow", "Cycle the glow through every color instead of using one.", flag(s.electricRainbow, true));
        if (!s.electricRainbow.get()) {
            f.row("Glow color", "The color of the glow around orbs and bolts.", color(s.electricGlowColor, true));
        }
        f.row("Core color", "The color at the center of a bolt.", color(s.electricCoreColor, true));

        f.section("Mouse");
        f.row("Strike the pointer", "Lightning reaches toward the mouse pointer.", flag(s.electricMouse, true));
        if (s.electricMouse.get()) {
            f.row("Reach", "How close the pointer must be to attract a bolt.", number(s.electricStrikeRadius, 20, 600, 20, 0));
        }

        f.section("Overall");
        f.row("Opacity", "Fade the whole effect toward the window color.", number(s.electricOpacity, 0, 1, 0.05, 2));
        f.endCard();
    }

    private void gradient(SettingsForm f) {
        AppearanceSettings s = draft;
        f.section("Colors");
        f.row("Start", "The color at the top left.", color(s.gradientStart, false));
        f.row("End", "The color at the bottom right.", color(s.gradientEnd, false));

        f.section("Motion");
        f.row("Scrolling", "Slide the gradient slowly across the window.", flag(s.gradientScrolling, true));
        if (s.gradientScrolling.get()) {
            f.row("Speed", "How fast it slides, in pixels a second.", number(s.gradientSpeed, 0, 300, 5, 0));
        }
        f.endCard();
    }

    private void solid(SettingsForm f) {
        AppearanceSettings s = draft;
        f.section("Color");
        f.row("Match the theme", "Use the window color of the selected theme, so it follows the theme when you change it.", flag(s.solidFollowTheme, true));
        if (!s.solidFollowTheme.get()) {
            f.row("Color", "The color behind the app.", color(s.solidColor, false));
        }
        f.endCard();
    }

    private void image(SettingsForm f) {
        AppearanceSettings s = draft;
        f.note("Panels cover most of the window. To see more of the picture, lower the opacity of the Panels color in the Theme tab.");
        new ImageSection(s.backgroundImage, "window", () -> preview.effect() instanceof ImageBackground i ? i : null, this::edited, this::restructured)
                .rows(f, "Picture");
        f.row("Match the theme behind it", "Show the theme's window color where the picture doesn't reach or is faded.", flag(s.imageBackdropFollowTheme, true));
        if (!s.imageBackdropFollowTheme.get()) {
            f.row("Color behind it", "Shown where the picture doesn't reach or is faded.", color(s.imageBackdrop, false));
        }
        f.endCard();
    }

    // Controls

    private SpinnerOverlay whole(Setting<Integer> setting, int min, int max, int step) {
        return SettingsUi.intSpinner(setting, min, max, step, this::edited);
    }

    private SpinnerOverlay number(Setting<Double> setting, double min, double max, double step, int decimals) {
        return SettingsUi.decSpinner(setting, min, max, step, decimals, this::edited);
    }

    // A toggle that adds or removes rows redraws the screen, the rest only update the preview.
    private ToggleSwitchOverlay flag(Setting<Boolean> setting, boolean changesRows) {
        return SettingsUi.toggle(setting, changesRows ? this::restructured : this::edited);
    }

    private ColorPickerOverlay color(Setting<String> setting, boolean opacity) {
        return SettingsUi.color(setting, opacity, this::edited);
    }

    private void edited() {
        if (preview != null) preview.show(draft, themeWindow.get());
        changed.run();
    }

    private void restructured() {
        changed.run();
        redraw.run();
    }
}
