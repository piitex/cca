package me.piitex.cca.theme;

import me.piitex.engine.ui.color.Color;

// Accent colors read from the live theme. Always call these when building, never store the result,
// so the next redraw after a theme change picks up the new colors.
public final class Palette {
    private Palette() {
    }

    // Active nav and tabs, primary buttons, toggles and selection.
    public static Color accent() {
        return Themes.current().accent();
    }

    // Start of the gradient, same as accent().
    public static Color accentStart() {
        return Themes.current().accent();
    }

    public static Color accentEnd() {
        return Themes.current().accentEnd();
    }

    public static Color accentHover() {
        return Themes.current().accentHover();
    }

    // Hovering a card or a row that isn't selected.
    public static Color rowHover() {
        return Themes.current().rowHover();
    }

    // A faint wash of the accent for selected rows.
    public static Color tint() {
        return Themes.current().tint();
    }

    public static Color tintStrong() {
        return Themes.current().tintStrong();
    }

    // The panel color at a share of its own opacity. 0.92 lets the background show through a little.
    public static Color panel(float opacity) {
        Color panel = Themes.current().getContainerBackground();
        return new Color(panel.getR(), panel.getG(), panel.getB(), panel.getA() * opacity);
    }
}
