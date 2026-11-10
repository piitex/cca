package me.piitex.cca.ui;

import me.piitex.cca.App;
import me.piitex.cca.theme.Palette;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

// Points at a field that has something wrong with it: a red border, the cursor in it and a small popup under it
// saying what's wrong. Only one field is marked at a time.
public final class FieldWarning {
    private static final double HEIGHT = 36;

    private static Element marked;
    private static Container popup;

    private FieldWarning() {
    }

    // The screen the field is on has to be drawn already (or about to be), the popup is placed on the next frame.
    public static void show(Element field, String message) {
        clear();
        marked = field;
        field.getStyling().setBorderColor(Components.DANGER);
        field.getStyling().setBorderThickness(2f);
        App.logger.info("Invalid entry: {}", message);

        Scheduler.runLater(() -> {
            if (marked != field) return;
            App.window.requestFocus(field);

            Container tip = tip(message);
            tip.setPosition(field.getAbsoluteX(), field.getAbsoluteY() + field.getHeight() + 6);
            popup = tip;
            App.window.showPopup(tip, field, () -> {
                if (popup == tip) popup = null;
                if (marked == field) unmark();
            });
        });
    }

    // Takes the warning off, for when the field is being fixed.
    public static void clear() {
        if (popup != null) {
            App.window.closePopup(); // Its dismiss callback takes the border off.
        }
        unmark();
    }

    private static void unmark() {
        if (marked == null) return;
        marked.getStyling().resetBorderColor();
        marked.getStyling().resetBorderThickness();
        marked = null;
    }

    private static Container tip(String message) {
        TextOverlay text = new TextOverlay(message, 13f, Palette.text());
        text.setTextColor(Palette.text());
        double iconSize = 16, padding = 12;

        Container tip = new Container(padding + iconSize + 8 + text.getWidth() + padding, HEIGHT);
        Components.fill(tip, Palette.panel(1f));
        tip.getStyling().setBorderColor(Components.DANGER);
        tip.getStyling().setBorderThickness(1.5f);
        tip.getStyling().setCornerRadius(10f);

        IconOverlay icon = new IconOverlay(Feather.ALERT_CIRCLE, iconSize, iconSize);
        icon.setTint(Components.DANGER);
        icon.setPosition(padding, (HEIGHT - iconSize) / 2);
        tip.addElement(icon);

        text.setPosition(padding + iconSize + 8, (HEIGHT - text.getHeight()) / 2);
        tip.addElement(text);
        return tip;
    }
}
