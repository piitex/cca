package me.piitex.cca.ui.creator;

import me.piitex.cca.backend.server.PromptBuilder;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.FieldWarning;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.theme.Theme;

import java.util.LinkedHashMap;
import java.util.Map;

// Custom placeholders for a character or user, like [age] = 18. Placed at the bottom of a creator step.
// Keeps its fields between redraws like the creators do.
final class PlaceholderList {
    private static final double ROW_HEIGHT = 36;

    private final MainMenu owner;
    private final Theme theme = Components.theme();
    private final TextFieldOverlay nameField;
    private final TextFieldOverlay valueField;
    private final Map<String, String> entries = new LinkedHashMap<>();

    PlaceholderList(MainMenu owner) {
        this.owner = owner;

        nameField = new TextFieldOverlay(10, ROW_HEIGHT);
        nameField.setPlaceholder("Name, e.g. age");
        nameField.onSubmit(text -> add());

        valueField = new TextFieldOverlay(10, ROW_HEIGHT);
        valueField.setPlaceholder("Value, e.g. 18");
        valueField.onSubmit(text -> add());
    }

    // The live map, the creator reads it when saving and fills it when editing.
    Map<String, String> entries() {
        return entries;
    }

    // Returns the y for the next one.
    double place(ScrollContainer scroll, String who, double y, double width) {
        TextOverlay label = new TextOverlay("Placeholders", 13f, Palette.muted());
        label.setPosition(0, y);
        scroll.addElement(label);
        y += label.getHeight() + 4;

        TextFlowOverlay hint = new TextFlowOverlay("Write [name] in a message, persona or lore entry and it's swapped for " + who
                + " value. {char} and {user} always work.", width, 12f, Palette.muted());
        hint.setTextColor(Palette.muted());
        hint.setPosition(0, y);
        scroll.addElement(hint);
        y += hint.getHeight() + 8;

        double buttonWidth = 90, gap = 10;
        double nameWidth = Math.max(0, (width - buttonWidth - 2 * gap) * 0.35);
        double valueWidth = Math.max(0, width - buttonWidth - 2 * gap - nameWidth);
        nameField.setWidth(nameWidth);
        nameField.setPosition(0, y);
        scroll.addElement(nameField);
        valueField.setWidth(valueWidth);
        valueField.setPosition(nameWidth + gap, y);
        scroll.addElement(valueField);

        ButtonOverlay add = Components.accentButton("Add", 13f, buttonWidth, ROW_HEIGHT);
        add.setPosition(width - buttonWidth, y);
        add.onAction(this::add);
        scroll.addElement(add);
        y += ROW_HEIGHT + 10;

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            y = placeEntry(scroll, entry.getKey(), entry.getValue(), y, width);
        }
        return y + 12;
    }

    private double placeEntry(ScrollContainer scroll, String name, String value, double y, double width) {
        Container row = new Container(width, ROW_HEIGHT);
        Components.fill(row, Palette.field());
        row.getStyling().setBorderThickness(1f);
        row.getStyling().setBorderColor(Palette.border());
        row.getStyling().setCornerRadius(10f);

        TextOverlay nameText = new TextOverlay("[" + name + "]", 13f, Palette.accent());
        nameText.setTextColor(Palette.accent());
        nameText.setPosition(12, (ROW_HEIGHT - nameText.getHeight()) / 2);
        row.addElement(nameText);

        TextOverlay valueText = new TextOverlay(value.isEmpty() ? "(empty)" : value, 13f, Palette.text());
        valueText.setPosition(12 + nameText.getWidth() + 12, (ROW_HEIGHT - valueText.getHeight()) / 2);
        row.addElement(valueText);

        Container remove = Components.iconButton(Feather.TRASH, "Remove placeholder", 28, 16, 8f, Palette.mutedColor(),
                Palette.buttonHover(), () -> {
                    entries.remove(name);
                    owner.renderContent();
                });
        remove.setPosition(width - 28 - 4, (ROW_HEIGHT - 28) / 2);
        row.addElement(remove);

        row.setPosition(0, y);
        scroll.addElement(row);
        return y + ROW_HEIGHT + 8;
    }

    private void add() {
        if (!commit()) {
            FieldWarning.show(nameField, "Placeholder name is required.");
            return;
        }
        owner.renderContent();
    }

    // Takes what's typed in the fields as a placeholder, for when saving without pressing Add. False if there's no name.
    // Brackets typed around the name are dropped, the same name again replaces its value.
    boolean commit() {
        String name = nameField.getText().replaceAll("^[\\[{\\s]+|[\\]}\\s]+$", "");
        if (name.isEmpty()) return false;
        entries.keySet().removeIf(existing -> PromptBuilder.placeholderKey(existing).equals(PromptBuilder.placeholderKey(name)));
        entries.put(name, valueField.getText().trim());
        nameField.setText("");
        valueField.setText("");
        return true;
    }
}
