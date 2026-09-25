package me.piitex.cca.ui.settings;

import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.theme.Theme;

import static me.piitex.cca.ui.settings.SettingsUi.FOOTER_HEIGHT;

// Status on the left ("Unsaved changes" / "Saved.") and Reset tab, Discard, Save & Apply on the right.
// Call update() after every edit. The save button fills with the accent while there's something to save.
final class SettingsFooter {
    private final Theme theme = Components.theme();
    private final Container row;
    private final TextOverlay status;
    private final ButtonOverlay save;

    SettingsFooter(double width, Runnable onSave, Runnable onDiscard, Runnable onResetTab) {
        row = Components.transparent(width, FOOTER_HEIGHT);

        double height = 38, gap = 10;
        double saveWidth = 140, discardWidth = 96, resetWidth = 116;

        save = new ButtonOverlay("Save & Apply", 14f, Color.WHITE, saveWidth, height);
        save.getStyling().setBorderThickness(0f);
        save.getStyling().setCornerRadius((float) (height / 2));
        save.setPosition(width - saveWidth, 0);
        save.onAction(onSave);
        row.addElement(save);

        ButtonOverlay discard = Components.secondaryButton("Discard", 13f, discardWidth, height);
        discard.setPosition(width - saveWidth - gap - discardWidth, 0);
        discard.onAction(onDiscard);
        row.addElement(discard);

        ButtonOverlay reset = Components.secondaryButton("Reset tab", 13f, resetWidth, height);
        reset.setPosition(width - saveWidth - discardWidth - 2 * gap - resetWidth, 0);
        reset.onAction(onResetTab);
        row.addElement(reset);

        status = new TextOverlay("", 13f, theme.getPlaceholderText());
        status.setPosition(0, (height - status.getHeight()) / 2.0);
        row.addElement(status);
    }

    Container element() {
        return row;
    }

    // savedMessage is shown in green when nothing is unsaved, null for nothing.
    void update(boolean dirty, String savedMessage) {
        if (dirty) {
            status.setText("Unsaved changes");
            status.setTextColor(Components.WARN);
        } else if (savedMessage != null) {
            status.setText(savedMessage);
            status.setTextColor(Components.VALID);
        } else {
            status.setText("");
        }
        save.getStyling().setBackgroundColor(dirty ? Palette.accent() : theme.getButtonBackground());
        save.getStyling().setHoverColor(dirty ? Palette.accentHover() : theme.getButtonHoverBackground());
        save.setTextColor(dirty ? Color.WHITE : theme.getPlaceholderText());
    }
}
