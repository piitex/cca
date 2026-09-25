package me.piitex.cca.ui.settings;

import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;

// The Characters tab. The hub's cards in a column on the left and the options beside them. Every control only restyles the
// preview, nothing here changes which rows exist so the screen never needs a redraw.
final class CharactersTab {
    private static final double GAP = 16;

    private final AppearanceSettings draft;
    private final ScrollMemory scroll;
    private final Runnable changed;

    private CardPreview preview;

    CharactersTab(AppearanceSettings draft, ScrollMemory scroll, Runnable changed) {
        this.draft = draft;
        this.scroll = scroll;
        this.changed = changed;
    }

    Element build(double width, double height) {
        Container holder = Components.transparent(width, height);
        preview = new CardPreview(height);
        preview.show(draft);
        preview.element().setPosition(0, 0);
        holder.addElement(preview.element());

        double formX = CardPreview.WIDTH + GAP;
        SettingsForm f = new SettingsForm(Math.max(0, width - formX), height, scroll);
        cards(f);
        popout(f);
        Element form = f.finish();
        form.setPosition(formX, 0);
        holder.addElement(form);
        return holder;
    }

    private void cards(SettingsForm f) {
        f.section("Cards");
        f.row("Card width", "How wide a card is. The picture is square, so this sets the height too.", SettingsUi.intSpinner(draft.cardWidth, 140, 240, 2, this::edited));
        f.row("Spacing", "The space between cards.", SettingsUi.intSpinner(draft.cardGap, 4, 48, 1, this::edited));
        f.row("Corner radius", "How rounded cards and the pictures on them are.", SettingsUi.intSpinner(draft.cardRadius, 0, 32, 1, this::edited));
        f.row("Card buttons", "Edit, duplicate and delete under the name. The right click menu has them too.", SettingsUi.toggle(draft.cardButtons, this::edited));
        f.row("Folder portraits", "Show the cards inside a folder on its card. Off shows a plain folder icon.", SettingsUi.toggle(draft.folderPortraits, this::edited));
        f.endCard();
    }

    private void popout(SettingsForm f) {
        f.section("Open folders");
        f.row("Columns", "The most cards side by side in an open folder. Fewer fit when the window is narrow.", SettingsUi.intSpinner(draft.popoutColumns, 2, 6, 1, changed));
        f.row("Opacity", "How solid the folder panel is. Lower lets the hub show through.", SettingsUi.decSpinner(draft.popoutOpacity, 0.1, 1.0, 0.05, 2, changed));
        f.endCard();
    }

    private void edited() {
        preview.show(draft);
        changed.run();
    }
}
