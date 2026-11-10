package me.piitex.cca.theme;

import me.piitex.cca.theme.AppTheme.Slot;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;

// Colors read from the live theme. Always call these when building, never store the result,
// so the next redraw after a theme change picks up the new colors.
// They're paints: a color, a gradient, a gradient that scrolls or a rainbow. Each call gives a copy of its own,
// as a scrolling gradient keeps its position and sharing one would make everything using it scroll faster.
// For things that can only take a Color, the Color methods give the color itself or a gradient's first.
public final class Palette {
    private Palette() {
    }

    private static Paint paint(Slot slot) {
        return Themes.current().fresh(slot);
    }

    // Active nav and tabs, primary buttons, toggles and selection.
    public static Paint accent() {
        return Themes.current().accent();
    }

    public static Color accentColor() {
        return Themes.current().accentColor();
    }

    // Start of the gradient, same as accent().
    public static Color accentStart() {
        return Themes.current().accentColor();
    }

    public static Color accentEnd() {
        return Themes.current().accentEnd();
    }

    public static Paint accentHover() {
        return paint(Slot.ACCENT_HOVER);
    }

    // Hovering a card or a row that isn't selected.
    public static Paint rowHover() {
        return paint(Slot.ROW_HOVER);
    }

    // A faint wash of the accent for selected rows.
    public static Paint tint() {
        return Themes.current().tint();
    }

    public static Paint tintStrong() {
        return Themes.current().tintStrong();
    }

    // Titles and body text.
    public static Paint text() {
        return paint(Slot.TEXT);
    }

    public static Color textColor() {
        return Themes.current().colorOf(Slot.TEXT);
    }

    // Hints, labels and inactive items.
    public static Paint muted() {
        return paint(Slot.MUTED);
    }

    public static Color mutedColor() {
        return Themes.current().colorOf(Slot.MUTED);
    }

    public static Paint border() {
        return paint(Slot.BORDER);
    }

    public static Paint button() {
        return paint(Slot.BUTTON);
    }

    public static Paint buttonHover() {
        return paint(Slot.BUTTON_HOVER);
    }

    public static Paint field() {
        return paint(Slot.FIELD);
    }

    public static Paint fieldHover() {
        return paint(Slot.FIELD_HOVER);
    }

    // The panel color at a share of its own opacity. 0.92 lets the background show through a little.
    public static Paint panel(float opacity) {
        return AppTheme.fade(Themes.current().get(Slot.PANEL), opacity);
    }
}
