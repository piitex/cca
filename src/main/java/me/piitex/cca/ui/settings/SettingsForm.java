package me.piitex.cca.ui.settings;

import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.SeparatorOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.theme.Theme;

import static me.piitex.cca.ui.settings.SettingsUi.CARD_PADDING;
import static me.piitex.cca.ui.settings.SettingsUi.ROW_PADDING;

// Builds a settings tab top to bottom in a scroll pane: notes, headings and cards of rows.
// Call finish() at the end to get the element.
final class SettingsForm {
    final double width;
    private final Theme theme = Components.theme();
    private final ScrollContainer scroll;
    private final ScrollMemory memory;
    private double y;
    private Container card;
    private double cursor;
    private int rows;

    // memory puts the scroll back where it was after a rebuild. Null always starts at the top.
    SettingsForm(double width, double height, ScrollMemory memory) {
        this.memory = memory;
        scroll = new ScrollContainer(width, height);
        double reserve = scroll.getScrollBarStyle().getThickness() + 2 * scroll.getScrollBarStyle().getMargin();
        this.width = Math.max(0, width - reserve);
    }

    void note(String text) {
        place(new TextFlowOverlay(text, width, 13f, theme.getPlaceholderText()), 18);
    }

    void heading(String title) {
        endCard();
        TextOverlay heading = new TextOverlay(title, 13f, theme.getPlaceholderText());
        heading.setPosition(4, y);
        scroll.addElement(heading);
        y += heading.getHeight() + 8;
    }

    // A heading with a new card under it for rows.
    void section(String title) {
        heading(title);
        card = SettingsUi.card(width, 0);
        card.setPosition(0, y);
        scroll.addElement(card);
        cursor = 0;
        rows = 0;
    }

    void endCard() {
        if (card == null) return;
        card.setHeight(cursor);
        y += cursor + 24;
        card = null;
    }

    // Adds an element on its own outside of a card.
    void place(Element element, double gap) {
        endCard();
        element.setPosition(0, y);
        scroll.addElement(element);
        y += element.getHeight() + gap;
    }

    // A full width element inside the card.
    void custom(Element element) {
        element.setPosition(CARD_PADDING, cursor + ROW_PADDING);
        card.addElement(element);
        cursor += 2 * ROW_PADDING + element.getHeight();
        rows++;
    }

    // Label and help text on the left, the control on the right.
    void row(String label, String help, Element control) {
        double inner = width - 2 * CARD_PADDING;
        double textWidth = Math.max(140, inner - control.getWidth() - 24);

        TextOverlay title = new TextOverlay(label, 14f, theme.getText());
        TextFlowOverlay description = help == null || help.isBlank() ? null : new TextFlowOverlay(help, textWidth, 12f, theme.getPlaceholderText());
        double textHeight = title.getHeight() + (description != null ? 3 + description.getHeight() : 0);
        double rowHeight = Math.max(textHeight, control.getHeight()) + 2 * ROW_PADDING;

        if (rows > 0) {
            SeparatorOverlay line = new SeparatorOverlay(inner);
            line.setLineColor(theme.getContainerBorder());
            line.setPosition(CARD_PADDING, cursor);
            card.addElement(line);
        }
        title.setPosition(CARD_PADDING, cursor + (rowHeight - textHeight) / 2.0);
        card.addElement(title);
        if (description != null) {
            description.setPosition(CARD_PADDING, title.getY() + title.getHeight() + 3);
            card.addElement(description);
        }
        control.setPosition(CARD_PADDING + inner - control.getWidth(), cursor + (rowHeight - control.getHeight()) / 2.0);
        card.addElement(control);

        cursor += rowHeight;
        rows++;
    }

    Element finish() {
        endCard();
        if (memory != null) memory.attach(scroll);
        return scroll;
    }
}
