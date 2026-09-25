package me.piitex.cca.ui.settings;

import me.piitex.cca.config.Settings.Setting;
import me.piitex.cca.theme.AppTheme;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.SpinnerOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// Sizes and controls shared by the Models and Settings screens so their rows line up the same.
// Every control writes straight to its Setting and then calls changed.
final class SettingsUi {
    static final double PANEL_PADDING = 26;
    static final double TAB_HEIGHT = 38;
    static final double FOOTER_HEIGHT = 40;
    static final double CARD_PADDING = 20;
    static final double ROW_PADDING = 13;
    static final double CONTROL_HEIGHT = 34;
    static final double SPINNER_WIDTH = 150;
    static final double COLOR_WIDTH = 150;
    static final double DROPDOWN_WIDTH = 210;
    static final double TEXT_WIDTH = 300;

    private SettingsUi() {
    }

    // A faint tint of the text color so cards look inset in both light and dark themes.
    static Color cardColor() {
        Color text = Components.theme().getText();
        return new Color(text.getR(), text.getG(), text.getB(), 0.045f);
    }

    static Container card(double width, double height) {
        Container card = new Container(width, height);
        Components.fill(card, cardColor());
        card.getStyling().setBorderThickness(1f);
        card.getStyling().setBorderColor(Components.theme().getContainerBorder());
        card.getStyling().setCornerRadius(16f);
        return card;
    }

    record OptionRow(Container row, TextOverlay title) {
    }

    // A row with a radio circle, a title and a subtitle. Used for picking a model or a theme.
    static OptionRow optionRow(double width, String title, String subtitle, boolean selected, Runnable onPick) {
        Theme theme = Components.theme();
        double height = 62;
        Container row = new Container(width, height);
        row.getStyling().setCornerRadius(14f);
        row.getStyling().setBackgroundColor(selected ? Palette.tint() : cardColor());
        row.getStyling().setHoverColor(selected ? Palette.tintStrong() : Palette.rowHover());
        row.getStyling().setBorderThickness(1f);
        row.getStyling().setBorderColor(selected ? Palette.accent() : theme.getContainerBorder());
        clickable(row, onPick);

        double radio = 18;
        Container ring = new Container(radio, radio);
        ring.getStyling().setBackgroundColor(Color.TRANSPARENT);
        ring.getStyling().setHoverColor(Color.TRANSPARENT);
        ring.getStyling().setBorderThickness(2f);
        ring.getStyling().setBorderColor(selected ? Palette.accent() : theme.getPlaceholderText());
        ring.getStyling().setCornerRadius((float) (radio / 2));
        ring.setPosition(CARD_PADDING - 2, (height - radio) / 2.0);
        clickable(ring, onPick);
        if (selected) {
            double dot = 8;
            Container inner = new Container(dot, dot);
            Components.fill(inner, Palette.accent());
            inner.getStyling().setBorderThickness(0f);
            inner.getStyling().setCornerRadius((float) (dot / 2));
            inner.setPosition((radio - dot) / 2.0 - 2, (radio - dot) / 2.0 - 2);
            clickable(inner, onPick);
            ring.addElement(inner);
        }
        row.addElement(ring);

        double textX = CARD_PADDING + radio + 14;
        TextOverlay name = new TextOverlay(title, 15f, theme.getText());
        TextOverlay kind = new TextOverlay(subtitle, 12f, theme.getPlaceholderText());
        double blockHeight = name.getHeight() + 3 + kind.getHeight();
        name.setPosition(textX, (height - blockHeight) / 2.0);
        kind.setPosition(textX, (height - blockHeight) / 2.0 + name.getHeight() + 3);
        for (TextOverlay text : new TextOverlay[]{name, kind}) {
            clickable(text, onPick);
            row.addElement(text);
        }
        return new OptionRow(row, name);
    }

    // Clicks on a child don't reach the parent, so each piece needs the handler.
    static void clickable(Element element, Runnable action) {
        element.setCursorMode(CursorMode.POINTER);
        element.onMouseClick(event -> action.run());
    }

    static SpinnerOverlay intSpinner(Setting<Integer> setting, int min, int max, int step, Runnable changed) {
        SpinnerOverlay spinner = new SpinnerOverlay(SPINNER_WIDTH, CONTROL_HEIGHT, min, max, setting.get());
        spinner.setStep(step);
        spinner.setValue(setting.get());
        spinner.onValueChange(v -> {
            setting.set((int) Math.round(v));
            changed.run();
        });
        return spinner;
    }

    static SpinnerOverlay decSpinner(Setting<Double> setting, double min, double max, double step, int decimals, Runnable changed) {
        SpinnerOverlay spinner = new SpinnerOverlay(SPINNER_WIDTH, CONTROL_HEIGHT, min, max, setting.get());
        spinner.setStep(step);
        spinner.setDecimals(decimals);
        spinner.setValue(setting.get());
        spinner.onValueChange(v -> {
            setting.set(v);
            changed.run();
        });
        return spinner;
    }

    static ToggleSwitchOverlay toggle(Setting<Boolean> setting, Runnable changed) {
        ToggleSwitchOverlay toggle = new ToggleSwitchOverlay(44, 24, setting.get());
        toggle.setOnColor(Palette.accent());
        toggle.onToggle(on -> {
            setting.set(on);
            changed.run();
        });
        return toggle;
    }

    static ColorPickerOverlay color(Setting<String> setting, boolean alpha, Runnable changed) {
        Color initial = AppTheme.parseHex(setting.get());
        ColorPickerOverlay picker = new ColorPickerOverlay(COLOR_WIDTH, CONTROL_HEIGHT, initial == null ? Color.WHITE : initial);
        picker.setAlphaEnabled(alpha);
        picker.onChange(picked -> {
            setting.set(AppTheme.toHex(picked));
            changed.run();
        });
        return picker;
    }

    static <T> DropdownOverlay<T> choice(Setting<T> setting, List<T> values, Function<T, String> label, Runnable changed) {
        DropdownOverlay<T> dropdown = new DropdownOverlay<>(DROPDOWN_WIDTH, CONTROL_HEIGHT);
        dropdown.setFontSize(14f);
        dropdown.setLabelProvider(label);
        List<T> items = new ArrayList<>(values);
        if (!items.contains(setting.get())) items.add(setting.get()); // A value from the file this list doesn't know about.
        dropdown.setItems(items);
        dropdown.setSelected(setting.get());
        dropdown.onSelect(value -> {
            setting.set(value);
            changed.run();
        });
        return dropdown;
    }

    static TextFieldOverlay text(Setting<String> setting, String placeholder, double width, Runnable changed) {
        TextFieldOverlay field = new TextFieldOverlay(width, CONTROL_HEIGHT);
        field.setPlaceholder(placeholder);
        field.setText(setting.get());
        field.onTextChanged(t -> {
            setting.set(t);
            changed.run();
        });
        return field;
    }

    static TextOverlay value(String text) {
        return new TextOverlay(text, 14f, Components.theme().getText());
    }
}
