package me.piitex.cca.ui.chat;

import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.theme.Theme;

import static me.piitex.cca.ui.Components.CONTROL_SIZE;

// The row of round buttons under a message: reply picker, copy, edit, regenerate, continue, delete.
// Every style uses this so the buttons are always the same and in the same order.
final class MessageActionBar {
    static final double HEIGHT = 32;
    private static final double GAP = 4;
    private static final double COUNTER_WIDTH = 38;

    private MessageActionBar() {
    }

    // Null if there's nothing to show. Check getWidth() to see how wide it ended up.
    static Container build(MessageActions actions) {
        Theme theme = Components.theme();
        if (actions.editing() != null) return buildEditing(actions.editing());
        MessageActions.Variants variants = actions.shownVariants();

        Runnable edit = actions.edit() == null ? null : () -> actions.edit().accept(-1);

        int buttons = 0;
        for (Object action : new Object[]{actions.copy(), edit, actions.regenerate(), actions.continueReply(), actions.delete()}) {
            if (action != null) buttons++;
        }
        if (buttons == 0 && variants == null) return null;

        int slots = buttons + (variants == null ? 0 : 2);
        int pieces = slots + (variants == null ? 0 : 1);
        double width = slots * CONTROL_SIZE + (variants == null ? 0 : COUNTER_WIDTH) + (pieces - 1) * GAP;
        Container bar = Components.transparent(width, HEIGHT);

        double x = 0;
        if (variants != null) {
            x = placeArrow(bar, Feather.CHEVRON_LEFT, variants.index() > 0, variants.previous(), ChatShortcuts.PREVIOUS.tooltip(), x);

            TextOverlay counter = new TextOverlay((variants.index() + 1) + "/" + variants.count(), 12f, theme.getPlaceholderText());
            counter.setPosition(x + (COUNTER_WIDTH - counter.getWidth()) / 2.0, (HEIGHT - counter.getHeight()) / 2.0);
            bar.addElement(counter);
            x += COUNTER_WIDTH + GAP;

            x = placeArrow(bar, Feather.CHEVRON_RIGHT, variants.index() < variants.count() - 1, variants.next(), ChatShortcuts.NEXT.tooltip(), x);
        }
        boolean keys = actions.newest();
        x = place(bar, Feather.COPY, Components.CONTROL_COPY, Components.CONTROL_COPY_HOVER, actions.copy(), "Copy", x);
        x = place(bar, Feather.EDIT, Components.CONTROL_EDIT, Components.CONTROL_EDIT_HOVER, edit, keys ? ChatShortcuts.EDIT.tooltip() : "Edit", x);
        x = place(bar, Feather.REFRESH_CW, Components.CONTROL_REGEN, Components.CONTROL_REGEN_HOVER, actions.regenerate(), ChatShortcuts.REGENERATE.tooltip(), x);
        x = place(bar, Feather.CHEVRONS_RIGHT, Components.CONTROL_CONTINUE, Components.CONTROL_CONTINUE_HOVER, actions.continueReply(), ChatShortcuts.CONTINUE.tooltip(), x);
        place(bar, Feather.TRASH, Components.CONTROL_DANGER, Components.CONTROL_DANGER_HOVER, actions.delete(), keys ? ChatShortcuts.DELETE.tooltip() : "Delete", x);
        return bar;
    }

    // Save and cancel while editing. Escape and Cmd/Ctrl+Enter do the same.
    private static Container buildEditing(MessageActions.Editing editing) {
        double buttonWidth = 78, buttonHeight = 30;
        Container bar = Components.transparent(2 * buttonWidth + 8, HEIGHT);

        ButtonOverlay cancel = Components.secondaryButton("Cancel", 13f, buttonWidth, buttonHeight);
        cancel.setPosition(0, (HEIGHT - buttonHeight) / 2);
        cancel.onAction(editing.cancel());
        bar.addElement(cancel);

        ButtonOverlay save = Components.accentButton("Save", 13f, buttonWidth, buttonHeight);
        save.setPosition(buttonWidth + 8, (HEIGHT - buttonHeight) / 2);
        save.onAction(editing.save());
        bar.addElement(save);
        return bar;
    }

    // At either end the arrow is dimmed and does nothing, so the counter doesn't jump around.
    private static double placeArrow(Container bar, IconAsset icon, boolean enabled, Runnable action, String tooltip, double x) {
        Theme theme = Components.theme();
        Color placeholder = theme.getPlaceholderText();
        Color dim = new Color(placeholder.getR(), placeholder.getG(), placeholder.getB(), 0.35f);
        Container button = enabled
                ? Components.iconButton(icon, tooltip, theme.getText(), Palette.tintStrong(), action)
                : Components.iconButton(icon, null, dim, Color.TRANSPARENT, () -> {});
        button.setPosition(x, 0);
        bar.addElement(button);
        return x + CONTROL_SIZE + GAP;
    }

    private static double place(Container bar, IconAsset icon, Color tint, Color hover, Runnable action, String tooltip, double x) {
        if (action == null) return x;
        Container button = Components.iconButton(icon, tooltip, tint, hover, action);
        button.setPosition(x, 0);
        bar.addElement(button);
        return x + CONTROL_SIZE + GAP;
    }
}
