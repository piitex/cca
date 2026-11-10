package me.piitex.cca.theme;

import me.piitex.cca.config.AppearanceSettings;
import me.piitex.engine.config.Config;
import me.piitex.engine.io.Json;
import me.piitex.engine.io.JsonWriter;
import me.piitex.engine.ui.color.AnimatedPaint;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.PaintCodec;
import me.piitex.engine.ui.color.RainbowColor;
import me.piitex.engine.ui.color.ScrollingGradient;
import me.piitex.engine.ui.color.Styling;
import me.piitex.engine.ui.theme.Theme;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A full look for the app: every color the engine's Theme needs, the accent pair used for the app's
 * own highlights, and the corner radius.
 * <p>
 * Every slot holds a {@link Paint}: a plain color, a gradient, a gradient that scrolls or a rainbow. The engine's
 * Theme asks for some slots as a Color, those get the paint itself when it is a color (a rainbow still cycles) and the
 * first color of a gradient otherwise. Our own widgets read the whole paint through {@link Palette}.
 * <p>
 * Only the editable slots show up in the theme editor. The rest are worked out from them, and changing
 * one color only recalculates the ones that follow it (a button's hover follows the button).
 * The built in themes copy the engine's themes exactly.
 */
public final class AppTheme implements Theme {

    public enum Slot {
        ACCENT("accent", "Accent", true),
        ACCENT_END("accentEnd", "Accent gradient end", true),
        WINDOW("window", "Window background", true),
        PANEL("panel", "Panels", true),
        FIELD("field", "Cards and inputs", true),
        TEXT("text", "Text", true),
        MUTED("muted", "Muted text", true),
        BUTTON("button", "Buttons", true),
        BORDER("border", "Borders", true),
        BUTTON_HOVER("buttonHover", "Button hover", true),
        FIELD_HOVER("fieldHover", "Input hover", true),
        ACCENT_HOVER("accentHover", "Accent button hover", true),
        ROW_HOVER("rowHover", "Card and row hover", true),


        FIELD_BORDER("fieldBorder", "", false),
        CARET("caret", "", false),
        SELECTION("selection", "", false),
        BUTTON_BORDER("buttonBorder", "", false),
        SCROLL_THUMB("scrollThumb", "", false),
        SCROLL_TRACK("scrollTrack", "", false),
        TOOLTIP_BACKGROUND("tooltipBackground", "", false),
        TOOLTIP_BORDER("tooltipBorder", "", false),
        TOOLTIP_TEXT("tooltipText", "", false);

        private final String key;
        private final String label;
        private final boolean editable;

        Slot(String key, String label, boolean editable) {
            this.key = key;
            this.label = label;
            this.editable = editable;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public boolean isEditable() {
            return editable;
        }

        // The second accent color is only ever the end of a two color gradient, so it stays a plain color.
        public boolean allowsPaint() {
            return this != ACCENT_END;
        }
    }

    public static final float MAX_CORNER_RADIUS = 16f;
    private static final float ROW_HOVER_ALPHA = 0.10f;
    // A theme file is not trusted, so a paint read from one is held to what the editor can make.
    public static final int MAX_STOPS = 5;
    public static final float MAX_SPEED = 600f;
    private static final int MAX_PAINT_TEXT = 4096;

    private final String id;
    private String name;
    private final boolean builtIn;
    private final Map<Slot, Paint> colors = new EnumMap<>(Slot.class);
    private float cornerRadius = 6f;
    // The background.* options this theme brings along, see AppearanceSettings. Null leaves the background alone.
    private Map<String, Object> background;

    private AppTheme(String id, String name, boolean builtIn) {
        this.id = id;
        this.name = name;
        this.builtIn = builtIn;
    }

    // Copies every color from an engine theme and adds the accent pair.
    public static AppTheme of(String id, String name, Theme base, Color accent, Color accentEnd) {
        AppTheme theme = new AppTheme(id, name, true);
        theme.colors.put(Slot.ACCENT, accent);
        theme.colors.put(Slot.ACCENT_END, accentEnd);
        theme.colors.put(Slot.WINDOW, base.getWindowBackground() instanceof Color c ? c : Color.WHITE);
        theme.colors.put(Slot.PANEL, base.getContainerBackground());
        theme.colors.put(Slot.FIELD, base.getFieldBackground());
        theme.colors.put(Slot.TEXT, base.getText());
        theme.colors.put(Slot.MUTED, base.getPlaceholderText());
        theme.colors.put(Slot.BUTTON, base.getButtonBackground());
        theme.colors.put(Slot.BORDER, base.getContainerBorder());
        theme.colors.put(Slot.FIELD_HOVER, base.getFieldHoverBackground());
        theme.colors.put(Slot.ACCENT_HOVER, lighten(accent));
        theme.colors.put(Slot.ROW_HOVER, withAlpha(accent, ROW_HOVER_ALPHA));
        theme.colors.put(Slot.FIELD_BORDER, base.getFieldBorder());
        theme.colors.put(Slot.CARET, base.getCaret());
        theme.colors.put(Slot.SELECTION, base.getSelection());
        theme.colors.put(Slot.BUTTON_HOVER, base.getButtonHoverBackground());
        theme.colors.put(Slot.BUTTON_BORDER, base.getButtonBorder());
        theme.colors.put(Slot.SCROLL_THUMB, base.getScrollThumb());
        theme.colors.put(Slot.SCROLL_TRACK, base.getScrollTrack());
        theme.colors.put(Slot.TOOLTIP_BACKGROUND, base.getTooltipBackground());
        theme.colors.put(Slot.TOOLTIP_BORDER, base.getTooltipBorder());
        theme.colors.put(Slot.TOOLTIP_TEXT, base.getTooltipText());
        theme.cornerRadius = base.getCornerRadius();
        return theme;
    }

    public AppTheme copy(String newId, String newName, boolean newBuiltIn) {
        AppTheme theme = new AppTheme(newId, newName, newBuiltIn);
        theme.colors.putAll(colors);
        theme.cornerRadius = cornerRadius;
        theme.background = background == null ? null : new LinkedHashMap<>(background);
        return theme;
    }

    public AppTheme copy() {
        return copy(id, name, builtIn);
    }

    // Takes the look (not the id) so the one live theme object can change into any theme.
    void takeLookFrom(AppTheme other) {
        colors.putAll(other.colors);
        cornerRadius = other.cornerRadius;
        name = other.name;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (!builtIn && name != null && !name.isBlank()) this.name = name.trim();
    }

    // Built in themes can't be edited or deleted, the user has to customize a copy.
    public boolean isBuiltIn() {
        return builtIn;
    }

    public Map<String, Object> background() {
        return background;
    }

    public void setBackground(Map<String, Object> background) {
        this.background = background == null ? null : new LinkedHashMap<>(background);
    }

    // The paint as it's stored. Anything that gives it to an element should use fresh, a scrolling gradient is state.
    public Paint get(Slot slot) {
        return colors.get(slot);
    }

    // A copy that's safe to hand to one element.
    public Paint fresh(Slot slot) {
        return Styling.ownCopy(colors.get(slot));
    }

    // A plain color for the slot: its first color for a gradient, and what a rainbow shows right now.
    public Color solid(Slot slot) {
        return solid(colors.get(slot));
    }

    // What the engine's Color getters give: the paint itself when it is a color (so a rainbow keeps cycling), else a plain one.
    public Color colorOf(Slot slot) {
        return colors.get(slot) instanceof Color color ? color : solid(slot);
    }

    // Setting an editable color also updates the colors that follow it. The window is always opaque.
    public void set(Slot slot, Paint paint) {
        paint = normalize(paint);
        if (!slot.allowsPaint()) paint = solid(paint);
        if (slot == Slot.WINDOW) paint = withAlpha(paint, 1f);
        colors.put(slot, paint);
        Color text = neutral(get(Slot.TEXT));
        switch (slot) {
            case ACCENT -> {
                colors.put(Slot.SELECTION, withAlpha(paint, 0.55f));
                colors.put(Slot.ACCENT_HOVER, lighten(paint));
                colors.put(Slot.ROW_HOVER, withAlpha(paint, ROW_HOVER_ALPHA));
            }
            case TEXT -> {
                colors.put(Slot.CARET, paint);
                colors.put(Slot.TOOLTIP_TEXT, paint);
                deriveChrome();
            }
            case PANEL -> deriveChrome();
            case FIELD -> colors.put(Slot.FIELD_HOVER, mix(paint, text, 0.07f));
            case BUTTON -> colors.put(Slot.BUTTON_HOVER, mix(paint, text, 0.10f));
            case BORDER -> {
                colors.put(Slot.FIELD_BORDER, mix(paint, text, 0.10f));
                colors.put(Slot.BUTTON_BORDER, mix(paint, text, 0.25f));
                colors.put(Slot.TOOLTIP_BORDER, mix(paint, text, 0.20f));
            }
            default -> {
            }
        }
    }

    // Scroll bars and tooltips stay opaque even on a see through panel so they're easy to see.
    private void deriveChrome() {
        Color panel = neutral(get(Slot.PANEL));
        Color text = neutral(get(Slot.TEXT));
        colors.put(Slot.SCROLL_THUMB, withAlpha(mix(panel, text, 0.60f), 1f));
        colors.put(Slot.SCROLL_TRACK, withAlpha(mix(panel, text, 0.20f), 1f));
        colors.put(Slot.TOOLTIP_BACKGROUND, withAlpha(mix(panel, text, 0.12f), 0.97f));
    }

    public float cornerRadiusValue() {
        return cornerRadius;
    }

    public void setCornerRadius(float radius) {
        this.cornerRadius = Math.max(0f, Math.min(MAX_CORNER_RADIUS, radius));
    }

    public Paint accent() {
        return fresh(Slot.ACCENT);
    }

    // The accent as a color, for what can't take a gradient.
    public Color accentColor() {
        return colorOf(Slot.ACCENT);
    }

    public Color accentEnd() {
        return solid(Slot.ACCENT_END);
    }

    // Hovering a button filled with the accent.
    public Paint accentHover() {
        return fresh(Slot.ACCENT_HOVER);
    }

    // Hovering a card or a row that isn't selected.
    public Paint rowHover() {
        return fresh(Slot.ROW_HOVER);
    }

    public Paint tint() {
        return withAlpha(get(Slot.ACCENT), 0.10f);
    }

    public Paint tintStrong() {
        return withAlpha(get(Slot.ACCENT), 0.18f);
    }

    // Compares paints the way they're saved, the radius, the name and the background. Not the id.
    public boolean sameLookAs(AppTheme other) {
        if (other == null || cornerRadius != other.cornerRadius || !name.equals(other.name)) return false;
        if (!Objects.equals(background, other.background)) return false;
        for (Slot slot : Slot.values()) {
            if (!encode(get(slot)).equals(encode(other.get(slot)))) return false;
        }
        return true;
    }

    public void writeTo(Config target) {
        target.set("name", name);
        target.set("radius", (double) cornerRadius);
        for (Slot slot : Slot.values()) {
            target.set("color." + slot.key(), encode(get(slot)));
        }
        if (background == null) return;
        target.set("background.linked", true);
        background.forEach(target::set);
    }

    // Anything missing or broken in the file falls back to the fallback theme's value.
    public static AppTheme readFrom(Config source, String id, AppTheme fallback) {
        AppTheme theme = fallback.copy(id, source.getString("name", id), false);
        theme.setCornerRadius((float) source.getDouble("radius", fallback.cornerRadius));
        for (Slot slot : Slot.values()) {
            Paint parsed = decode(source.getString("color." + slot.key(), null));
            if (parsed == null) continue;
            if (!slot.allowsPaint()) parsed = solid(parsed);
            theme.colors.put(slot, slot == Slot.WINDOW ? withAlpha(parsed, 1f) : parsed);
        }
        if (source.getBoolean("background.linked", false)) theme.background = AppearanceSettings.readBackground(source);
        // Themes saved before these hovers existed follow their own accent, not the fallback's.
        if (parseHex(source.getString("color." + Slot.ACCENT_HOVER.key(), null)) == null) {
            theme.colors.put(Slot.ACCENT_HOVER, lighten(theme.get(Slot.ACCENT)));
        }
        if (parseHex(source.getString("color." + Slot.ROW_HOVER.key(), null)) == null) {
            theme.colors.put(Slot.ROW_HOVER, withAlpha(theme.get(Slot.ACCENT), ROW_HOVER_ALPHA));
        }
        return theme;
    }

    public static String toHex(Color c) {
        return String.format("#%02X%02X%02X%02X", channel(c.getR()), channel(c.getG()), channel(c.getB()), channel(c.getA()));
    }

    // #RRGGBB or #RRGGBBAA, the # is optional. Null if it can't be read.
    public static Color parseHex(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6 && s.length() != 8) return null;
        try {
            int r = Integer.parseInt(s.substring(0, 2), 16);
            int g = Integer.parseInt(s.substring(2, 4), 16);
            int b = Integer.parseInt(s.substring(4, 6), 16);
            int a = s.length() == 8 ? Integer.parseInt(s.substring(6, 8), 16) : 255;
            return new Color(r / 255f, g / 255f, b / 255f, a / 255f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // A color is its hex, so themes saved before paints still read. Anything else is the codec's JSON on one line.
    public static String encode(Paint paint) {
        Object json = PaintCodec.HEX.write(paint);
        if (json instanceof String hex) return hex;
        if (json == null) return toHex(solid(paint));
        return JsonWriter.writeCompact(json);
    }

    // Null if the text isn't a color or a paint the editor could have made.
    public static Paint decode(String text) {
        if (text == null || text.length() > MAX_PAINT_TEXT) return null;
        String s = text.trim();
        if (!s.startsWith("{")) return parseHex(s);
        try {
            return normalize(PaintCodec.HEX.read(Json.parse(s)));
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    private static int channel(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    private static float unit(float v) {
        return Float.isFinite(v) ? Math.max(0f, Math.min(1f, v)) : 0f;
    }

    private static Color clamped(Color c) {
        return new Color(unit(c.getR()), unit(c.getG()), unit(c.getB()), unit(c.getA()));
    }

    // Keeps a paint to what the editor can make: a few colors, every channel in range, a speed and a cycle that are sane.
    // A paint of any other kind is reduced to its color.
    static Paint normalize(Paint paint) {
        Paint p = AnimatedPaint.resolve(paint);
        if (p instanceof RainbowColor r) {
            float seconds = r.getCycleDuration();
            if (!Float.isFinite(seconds)) seconds = 6f;
            if (seconds != 0f) seconds = Math.copySign(Math.max(0.5f, Math.min(120f, Math.abs(seconds))), seconds);
            RainbowColor copy = new RainbowColor(seconds, unit(r.getSaturation()), unit(r.getBrightness()), unit(r.getAlpha()));
            copy.setHueOffset(Float.isFinite(r.getHueOffset()) ? r.getHueOffset() : 0f);
            return copy;
        }
        if (p instanceof LinearGradient g) {
            List<Color> stops = new ArrayList<>();
            for (Color stop : g.getColors()) {
                if (stops.size() < MAX_STOPS) stops.add(clamped(stop));
            }
            float angle = Float.isFinite(g.getAngle()) ? ((g.getAngle() % 360f) + 360f) % 360f : Float.NaN;
            if (g instanceof ScrollingGradient scrolling) {
                float speed = Float.isFinite(scrolling.getSpeed()) ? Math.max(-MAX_SPEED, Math.min(MAX_SPEED, scrolling.getSpeed())) : 0f;
                return new ScrollingGradient(stops, angle, speed);
            }
            return new LinearGradient(stops, angle);
        }
        if (p instanceof Color c) return clamped(c);
        return Color.TRANSPARENT;
    }

    // A color that stays the same, whatever the paint is.
    static Color solid(Paint paint) {
        Paint p = AnimatedPaint.resolve(paint);
        if (p instanceof RainbowColor rainbow) return rainbow.snapshot();
        if (p instanceof LinearGradient gradient) return gradient.getStartColor();
        if (p instanceof Color color) return color;
        return Color.TRANSPARENT;
    }

    // Like solid, but a rainbow is a gray of its brightness. The scroll bars and tooltips are worked out once and saved,
    // they shouldn't keep whatever hue the rainbow was showing at that moment.
    private static Color neutral(Paint paint) {
        if (AnimatedPaint.resolve(paint) instanceof RainbowColor rainbow) {
            float level = rainbow.getBrightness();
            return new Color(level, level, level, rainbow.getAlpha());
        }
        return solid(paint);
    }

    // Rebuilds a paint with its colors changed, keeping a gradient's angle and speed and a rainbow's cycle.
    private static Paint transform(Paint paint, UnaryOperator<Color> stops, UnaryOperator<RainbowColor> rainbow) {
        Paint p = AnimatedPaint.resolve(paint);
        if (p instanceof RainbowColor r) return rainbow.apply(r);
        if (p instanceof LinearGradient g) {
            List<Color> mapped = new ArrayList<>();
            for (Color stop : g.getColors()) mapped.add(stops.apply(stop));
            if (g instanceof ScrollingGradient scrolling) return new ScrollingGradient(mapped, g.getAngle(), scrolling.getSpeed());
            return new LinearGradient(mapped, g.getAngle());
        }
        return stops.apply(p instanceof Color color ? color : Color.TRANSPARENT);
    }

    private static RainbowColor rainbow(RainbowColor from, float saturation, float brightness, float alpha) {
        RainbowColor copy = new RainbowColor(from.getCycleDuration(), saturation, brightness, alpha);
        copy.setHueOffset(from.getHueOffset());
        return copy;
    }

    static Paint withAlpha(Paint paint, float alpha) {
        return transform(paint, c -> new Color(c.getR(), c.getG(), c.getB(), alpha),
                r -> rainbow(r, r.getSaturation(), r.getBrightness(), alpha));
    }

    static Color withAlpha(Color c, float alpha) {
        return new Color(c.getR(), c.getG(), c.getB(), alpha);
    }

    // Keeps this share of the paint's own opacity.
    public static Paint fade(Paint paint, float share) {
        return transform(paint, c -> new Color(c.getR(), c.getG(), c.getB(), c.getA() * share),
                r -> rainbow(r, r.getSaturation(), r.getBrightness(), r.getAlpha() * share));
    }

    // A bit lighter, the default for hovering the accent. A rainbow gets paler.
    static Paint lighten(Paint paint) {
        return transform(paint, AppTheme::lighten, r -> rainbow(r, Math.max(0f, r.getSaturation() - 0.25f), r.getBrightness(), r.getAlpha()));
    }

    private static Color lighten(Color a) {
        return new Color(Math.min(1f, a.getR() + 0.10f), Math.min(1f, a.getG() + 0.10f), Math.min(1f, a.getB() + 0.10f), a.getA());
    }

    // Blends some of toward into the paint, keeping its alpha. A rainbow gets paler toward light text and darker toward dark text.
    static Paint mix(Paint paint, Color toward, float amount) {
        boolean light = (toward.getR() + toward.getG() + toward.getB()) / 3f > 0.5f;
        return transform(paint, c -> mix(c, toward, amount), r -> light
                ? rainbow(r, Math.max(0f, r.getSaturation() - amount * 2f), r.getBrightness(), r.getAlpha())
                : rainbow(r, r.getSaturation(), Math.max(0f, r.getBrightness() - amount * 2f), r.getAlpha()));
    }

    // Blends some of toward into base, keeping base's alpha.
    static Color mix(Color base, Color toward, float amount) {
        return new Color(
                base.getR() + (toward.getR() - base.getR()) * amount,
                base.getG() + (toward.getG() - base.getG()) * amount,
                base.getB() + (toward.getB() - base.getB()) * amount,
                base.getA());
    }

    @Override
    public Color getText() {
        return colorOf(Slot.TEXT);
    }

    @Override
    public Color getFieldBackground() {
        return colorOf(Slot.FIELD);
    }

    @Override
    public Color getFieldHoverBackground() {
        return colorOf(Slot.FIELD_HOVER);
    }

    @Override
    public Color getFieldBorder() {
        return colorOf(Slot.FIELD_BORDER);
    }

    @Override
    public Color getPlaceholderText() {
        return colorOf(Slot.MUTED);
    }

    @Override
    public Color getCaret() {
        return colorOf(Slot.CARET);
    }

    @Override
    public Color getSelection() {
        return colorOf(Slot.SELECTION);
    }

    @Override
    public Color getButtonBackground() {
        return colorOf(Slot.BUTTON);
    }

    @Override
    public Color getButtonHoverBackground() {
        return colorOf(Slot.BUTTON_HOVER);
    }

    @Override
    public Color getButtonBorder() {
        return colorOf(Slot.BUTTON_BORDER);
    }

    @Override
    public Paint getContainerBackground() {
        return get(Slot.PANEL);
    }

    @Override
    public Color getContainerBorder() {
        return colorOf(Slot.BORDER);
    }

    @Override
    public Color getScrollThumb() {
        return colorOf(Slot.SCROLL_THUMB);
    }

    @Override
    public Color getScrollTrack() {
        return colorOf(Slot.SCROLL_TRACK);
    }

    @Override
    public Paint getWindowBackground() {
        return get(Slot.WINDOW);
    }

    @Override
    public float getCornerRadius() {
        return cornerRadius;
    }

    @Override
    public float getBorderThickness() {
        return 1f;
    }

    @Override
    public Paint getTooltipBackground() {
        return get(Slot.TOOLTIP_BACKGROUND);
    }

    @Override
    public Paint getTooltipBorder() {
        return get(Slot.TOOLTIP_BORDER);
    }

    @Override
    public Paint getTooltipText() {
        return get(Slot.TOOLTIP_TEXT);
    }
}
