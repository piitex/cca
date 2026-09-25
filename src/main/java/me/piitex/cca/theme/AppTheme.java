package me.piitex.cca.theme;

import me.piitex.cca.config.AppearanceSettings;
import me.piitex.engine.config.Config;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.theme.Theme;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A full look for the app: every color the engine's Theme needs, the accent pair used for the app's
 * own highlights, and the corner radius.
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
    }

    public static final float MAX_CORNER_RADIUS = 16f;
    private static final float ROW_HOVER_ALPHA = 0.10f;

    private final String id;
    private String name;
    private final boolean builtIn;
    private final Map<Slot, Color> colors = new EnumMap<>(Slot.class);
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

    public Color get(Slot slot) {
        return colors.get(slot);
    }

    // Setting an editable color also updates the colors that follow it. The window is always opaque.
    public void set(Slot slot, Color color) {
        if (slot == Slot.WINDOW) color = withAlpha(color, 1f);
        colors.put(slot, color);
        Color text = get(Slot.TEXT);
        switch (slot) {
            case ACCENT -> {
                colors.put(Slot.SELECTION, withAlpha(color, 0.55f));
                colors.put(Slot.ACCENT_HOVER, lighten(color));
                colors.put(Slot.ROW_HOVER, withAlpha(color, ROW_HOVER_ALPHA));
            }
            case TEXT -> {
                colors.put(Slot.CARET, color);
                colors.put(Slot.TOOLTIP_TEXT, color);
                deriveChrome();
            }
            case PANEL -> deriveChrome();
            case FIELD -> colors.put(Slot.FIELD_HOVER, mix(color, text, 0.07f));
            case BUTTON -> colors.put(Slot.BUTTON_HOVER, mix(color, text, 0.10f));
            case BORDER -> {
                colors.put(Slot.FIELD_BORDER, mix(color, text, 0.10f));
                colors.put(Slot.BUTTON_BORDER, mix(color, text, 0.25f));
                colors.put(Slot.TOOLTIP_BORDER, mix(color, text, 0.20f));
            }
            default -> {
            }
        }
    }

    // Scroll bars and tooltips stay opaque even on a see through panel so they're easy to see.
    private void deriveChrome() {
        Color panel = get(Slot.PANEL);
        Color text = get(Slot.TEXT);
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

    public Color accent() {
        return get(Slot.ACCENT);
    }

    public Color accentEnd() {
        return get(Slot.ACCENT_END);
    }

    // Hovering a button filled with the accent.
    public Color accentHover() {
        return get(Slot.ACCENT_HOVER);
    }

    // Hovering a card or a row that isn't selected.
    public Color rowHover() {
        return get(Slot.ROW_HOVER);
    }

    // A bit lighter, the default for hovering the accent.
    private static Color lighten(Color a) {
        return new Color(Math.min(1f, a.getR() + 0.10f), Math.min(1f, a.getG() + 0.10f), Math.min(1f, a.getB() + 0.10f), a.getA());
    }

    public Color tint() {
        return withAlpha(accent(), 0.10f);
    }

    public Color tintStrong() {
        return withAlpha(accent(), 0.18f);
    }

    // Compares colors the way they're saved (8 bits a channel), the radius, the name and the background. Not the id.
    public boolean sameLookAs(AppTheme other) {
        if (other == null || cornerRadius != other.cornerRadius || !name.equals(other.name)) return false;
        if (!Objects.equals(background, other.background)) return false;
        for (Slot slot : Slot.values()) {
            if (!toHex(get(slot)).equals(toHex(other.get(slot)))) return false;
        }
        return true;
    }

    public void writeTo(Config target) {
        target.set("name", name);
        target.set("radius", (double) cornerRadius);
        for (Slot slot : Slot.values()) {
            target.set("color." + slot.key(), toHex(get(slot)));
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
            Color parsed = parseHex(source.getString("color." + slot.key(), null));
            if (parsed != null) theme.colors.put(slot, slot == Slot.WINDOW ? withAlpha(parsed, 1f) : parsed);
        }
        if (source.getBoolean("background.linked", false)) theme.background = AppearanceSettings.readBackground(source);
        // Themes saved before these hovers existed follow their own accent, not the fallback's.
        if (parseHex(source.getString("color." + Slot.ACCENT_HOVER.key(), null)) == null) {
            theme.colors.put(Slot.ACCENT_HOVER, lighten(theme.accent()));
        }
        if (parseHex(source.getString("color." + Slot.ROW_HOVER.key(), null)) == null) {
            theme.colors.put(Slot.ROW_HOVER, withAlpha(theme.accent(), ROW_HOVER_ALPHA));
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

    private static int channel(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    static Color withAlpha(Color c, float alpha) {
        return new Color(c.getR(), c.getG(), c.getB(), alpha);
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
        return get(Slot.TEXT);
    }

    @Override
    public Color getFieldBackground() {
        return get(Slot.FIELD);
    }

    @Override
    public Color getFieldHoverBackground() {
        return get(Slot.FIELD_HOVER);
    }

    @Override
    public Color getFieldBorder() {
        return get(Slot.FIELD_BORDER);
    }

    @Override
    public Color getPlaceholderText() {
        return get(Slot.MUTED);
    }

    @Override
    public Color getCaret() {
        return get(Slot.CARET);
    }

    @Override
    public Color getSelection() {
        return get(Slot.SELECTION);
    }

    @Override
    public Color getButtonBackground() {
        return get(Slot.BUTTON);
    }

    @Override
    public Color getButtonHoverBackground() {
        return get(Slot.BUTTON_HOVER);
    }

    @Override
    public Color getButtonBorder() {
        return get(Slot.BUTTON_BORDER);
    }

    @Override
    public Color getContainerBackground() {
        return get(Slot.PANEL);
    }

    @Override
    public Color getContainerBorder() {
        return get(Slot.BORDER);
    }

    @Override
    public Color getScrollThumb() {
        return get(Slot.SCROLL_THUMB);
    }

    @Override
    public Color getScrollTrack() {
        return get(Slot.SCROLL_TRACK);
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
    public Color getTooltipBackground() {
        return get(Slot.TOOLTIP_BACKGROUND);
    }

    @Override
    public Color getTooltipBorder() {
        return get(Slot.TOOLTIP_BORDER);
    }

    @Override
    public Color getTooltipText() {
        return get(Slot.TOOLTIP_TEXT);
    }
}
