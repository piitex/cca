package me.piitex.cca.ui.creator;

import me.piitex.cca.App;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ModalContainer;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextAreaOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.theme.Theme;

import java.util.LinkedHashMap;
import java.util.Map;

// The lorebook step of the character and user creators. Keeps its fields between redraws like the creators do.
final class LorebookStep {
    private static final double CARD_PADDING = 14;

    private final CreatorMenu creator;
    private final MainMenu owner;
    private final Theme theme = Components.theme();
    private final TextFieldOverlay keywordField;
    private final TextAreaOverlay contentField;
    private final Map<String, String> entries = new LinkedHashMap<>();

    LorebookStep(CreatorMenu creator, MainMenu owner) {
        this.creator = creator;
        this.owner = owner;

        keywordField = new TextFieldOverlay(10, 36);
        keywordField.setPlaceholder("Keyword, e.g. sword");

        contentField = new TextAreaOverlay(10, 90);
        contentField.setPlaceholder("What the AI should know when this keyword comes up...");
    }

    // The live map, the creator reads it when saving and fills it when editing or importing.
    Map<String, String> entries() {
        return entries;
    }

    Element build(double width, double height) {
        ScrollContainer scroll = new ScrollContainer(width, height);
        double fieldWidth = creator.fieldWidth(scroll, width);

        TextFlowOverlay intro = new TextFlowOverlay(
                "Optional. Add a keyword and what the AI should know when it comes up in the chat — "
                        + "its entry is folded into the prompt whenever the keyword appears.",
                fieldWidth, 12f, theme.getPlaceholderText());
        intro.setPosition(0, 0);
        scroll.addElement(intro);
        double y = intro.getHeight() + 16;

        y = creator.placeField(scroll, "Keyword", keywordField, y, fieldWidth, 36);
        y = creator.placeField(scroll, "Entry", contentField, y, fieldWidth, 90);

        ButtonOverlay add = Components.accentButton("Add Entry", 13f, 140, 36);
        add.setPosition(0, y);
        add.onAction(this::add);
        scroll.addElement(add);
        y += 36 + 22;

        if (entries.isEmpty()) {
            TextOverlay empty = new TextOverlay("No entries yet.", 12f, theme.getPlaceholderText());
            empty.setPosition(0, y);
            scroll.addElement(empty);
        } else {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                y = placeEntry(scroll, entry.getKey(), entry.getValue(), y, fieldWidth);
            }
        }
        return scroll;
    }

    // A saved entry with edit and delete. Edit opens a dialog instead of reusing the fields above so it's
    // obvious which entry is being edited, even when the list is scrolled.
    private double placeEntry(ScrollContainer scroll, String keyword, String content, double y, double width) {
        double actionsWidth = 62;
        double innerWidth = Math.max(0, width - 2 * CARD_PADDING - actionsWidth);

        TextOverlay keywordText = new TextOverlay(keyword, 13f, Palette.accent());
        TextFlowOverlay contentText = new TextFlowOverlay(content.isEmpty() ? "(empty)" : content, innerWidth, 12f, theme.getPlaceholderText());
        double cardHeight = CARD_PADDING + keywordText.getHeight() + 6 + contentText.getHeight() + CARD_PADDING;

        Container card = new Container(width, cardHeight);
        Components.fill(card, theme.getFieldBackground());
        card.getStyling().setBorderThickness(1f);
        card.getStyling().setBorderColor(theme.getContainerBorder());
        card.getStyling().setCornerRadius(14f);

        keywordText.setPosition(CARD_PADDING, CARD_PADDING);
        card.addElement(keywordText);

        contentText.setPosition(CARD_PADDING, CARD_PADDING + keywordText.getHeight() + 6);
        card.addElement(contentText);

        Color tint = theme.getPlaceholderText();
        Color hover = theme.getButtonHoverBackground();
        Container editButton = Components.iconButton(Feather.EDIT, "Edit entry", 28, 16, 8f, tint, hover, () -> edit(keyword, content));
        editButton.setPosition(width - CARD_PADDING - 2 * 28 - 6, CARD_PADDING - 4);
        card.addElement(editButton);

        Container removeButton = Components.iconButton(Feather.TRASH, "Remove entry", 28, 16, 8f, tint, hover, () -> {
            entries.remove(keyword);
            owner.renderContent();
        });
        removeButton.setPosition(width - CARD_PADDING - 28, CARD_PADDING - 4);
        card.addElement(removeButton);

        card.setPosition(0, y);
        scroll.addElement(card);
        return y + cardHeight + 14;
    }

    private void add() {
        String keyword = keywordField.getText().trim();
        if (keyword.isEmpty()) return;
        entries.put(keyword, contentField.getText().trim());
        keywordField.setText("");
        contentField.setText("");
        owner.renderContent();
    }

    private void edit(String keyword, String content) {
        double width = 380, fieldWidth = width - 48;
        VerticalLayout panel = Components.dialogPanel(width, 340);
        panel.addElement(new TextOverlay("Edit Lore Entry", 16f, theme.getText()));

        TextFieldOverlay keywordInput = new TextFieldOverlay(fieldWidth, 36);
        keywordInput.setPlaceholder("Keyword, e.g. sword");
        keywordInput.setText(keyword);
        panel.addElement(keywordInput);

        TextAreaOverlay contentInput = new TextAreaOverlay(fieldWidth, 150);
        contentInput.setPlaceholder("What the AI should know when this keyword comes up...");
        contentInput.setText(content);
        panel.addElement(contentInput);

        ModalContainer modal = new ModalContainer(panel);
        ButtonOverlay save = Components.accentButton("Save", 13f, (fieldWidth - 12) / 2, 38);
        save.onAction(() -> {
            String newKeyword = keywordInput.getText().trim();
            if (newKeyword.isEmpty()) return;
            // A rename, so drop the old key instead of leaving a duplicate behind.
            if (!newKeyword.equals(keyword)) {
                entries.remove(keyword);
            }
            entries.put(newKeyword, contentInput.getText().trim());
            modal.close();
            owner.renderContent();
        });
        panel.addElement(Components.dialogButtons(fieldWidth, modal, save));
        modal.show(App.window);
    }
}
