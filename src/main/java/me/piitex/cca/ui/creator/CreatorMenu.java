package me.piitex.cca.ui.creator;

import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.ContentMenu;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.ImageOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.theme.Theme;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The shared layout of the character and user creators: a row of step pills on top, the step's fields in the
 * middle and a Next button at the bottom. The last step is the finish step and has no Next button.
 * Any pill can be clicked to jump to that step.
 * <p>
 * The fields are created once and kept, only the layout is rebuilt on each redraw, so nothing typed is lost.
 */
public abstract class CreatorMenu implements ContentMenu {
    protected final Theme theme = Components.theme();
    protected final MainMenu owner;

    private static final double PANEL_PADDING = 26;
    private static final double STEP_BADGE = 26;
    private static final double PILL_PAD_X = 10;
    private static final double PILL_PAD_Y = 8;
    private static final double PILL_HEIGHT = STEP_BADGE + 2 * PILL_PAD_Y;
    private static final double STEP_GAP = 20;
    private static final double NAV_ROW_HEIGHT = 48;
    private static final double ICON_PREVIEW_SIZE = 84;

    private record StepPill(Container badge, Container dot, TextOverlay label) {
    }

    private final List<StepPill> stepPills = new ArrayList<>();
    private int activeStep = 0;

    // The icon they picked. It's only copied into the character/user folder when they save.
    protected Path pendingIconFile;
    protected String error;

    protected CreatorMenu(MainMenu owner) {
        this.owner = owner;
    }

    protected abstract String[] stepLabels();

    protected abstract Element buildStep(int step, double width, double height);

    // Shows the green dot on the step's pill.
    protected abstract boolean isStepValid(int step);

    private boolean isFinishStep() {
        return activeStep == stepLabels().length - 1;
    }

    @Override
    public Element render(double width, double height) {
        Container panel = Components.panel(width, height);
        double innerWidth = Math.max(0, width - 2 * PANEL_PADDING);

        Element stepper = buildStepper(innerWidth);
        stepper.setPosition(PANEL_PADDING, PANEL_PADDING);
        panel.addElement(stepper);

        double bodyY = PANEL_PADDING + stepper.getHeight() + 24;
        double reservedBottom = isFinishStep() ? 0 : NAV_ROW_HEIGHT + 16;
        double bodyHeight = Math.max(0, height - PANEL_PADDING - bodyY - reservedBottom);

        Element body = buildStep(activeStep, innerWidth, bodyHeight);
        body.setPosition(PANEL_PADDING, bodyY);
        panel.addElement(body);

        if (!isFinishStep()) {
            Element nav = buildNavRow(innerWidth);
            nav.setPosition(PANEL_PADDING, height - PANEL_PADDING - NAV_ROW_HEIGHT);
            panel.addElement(nav);
        }
        return panel;
    }

    // Step pills

    private Element buildStepper(double width) {
        Container row = Components.transparent(width, PILL_HEIGHT);
        stepPills.clear();

        String[] labels = stepLabels();
        double cursorX = 0;
        for (int i = 0; i < labels.length; i++) {
            Container pill = buildStepPill(i, labels[i]);
            pill.setPosition(cursorX, 0);
            row.addElement(pill);
            cursorX += pill.getWidth() + STEP_GAP;
        }

        highlightSteps();
        return row;
    }

    // The click area is padded well past the number and label so it's easy to hit.
    // Every child gets the click handler too since clicks on a child don't reach the pill.
    private Container buildStepPill(int step, String label) {
        TextOverlay labelText = new TextOverlay(label, 13f, theme.getPlaceholderText());
        double pillWidth = 2 * PILL_PAD_X + STEP_BADGE + 10 + labelText.getWidth();

        Container box = new Container(pillWidth, PILL_HEIGHT);
        box.getStyling().setBackgroundColor(Color.TRANSPARENT);
        box.getStyling().setHoverColor(theme.getButtonHoverBackground());
        box.getStyling().setBorderThickness(0f);
        box.getStyling().setCornerRadius(10f);
        clickable(box, step);

        Container badge = new Container(STEP_BADGE, STEP_BADGE);
        badge.getStyling().setCornerRadius((float) (STEP_BADGE / 2));
        clickable(badge, step);
        TextOverlay numberText = new TextOverlay(String.valueOf(step + 1), 12f, Color.WHITE);
        numberText.setPosition((STEP_BADGE - numberText.getWidth()) / 2.0, (STEP_BADGE - numberText.getHeight()) / 2.0);
        clickable(numberText, step);
        badge.addElement(numberText);
        badge.setPosition(PILL_PAD_X, PILL_PAD_Y);
        box.addElement(badge);

        // A small dot on the badge's corner when the step is filled in.
        Container dot = new Container(9, 9);
        dot.getStyling().setCornerRadius(4.5f);
        dot.setPosition(PILL_PAD_X + STEP_BADGE - 8, PILL_PAD_Y + STEP_BADGE - 8);
        clickable(dot, step);
        box.addElement(dot);

        labelText.setPosition(PILL_PAD_X + STEP_BADGE + 10, PILL_PAD_Y + (STEP_BADGE - labelText.getHeight()) / 2.0);
        clickable(labelText, step);
        box.addElement(labelText);

        stepPills.add(new StepPill(badge, dot, labelText));
        return box;
    }

    private void clickable(Element element, int step) {
        element.setCursorMode(CursorMode.POINTER);
        element.onMouseClick(event -> goToStep(step));
    }

    private void goToStep(int step) {
        if (step == activeStep) return;
        activeStep = step;
        owner.renderContent();
    }

    private void advanceStep() {
        goToStep(Math.min(activeStep + 1, stepLabels().length - 1));
    }

    // Called when a field changes so the dots update without a full redraw.
    protected void highlightSteps() {
        for (int i = 0; i < stepPills.size(); i++) {
            StepPill pill = stepPills.get(i);
            boolean active = i == activeStep;
            Components.fill(pill.badge(), active ? Palette.accent() : theme.getContainerBorder());
            pill.label().setTextColor(active ? theme.getText() : theme.getPlaceholderText());
            Components.fill(pill.dot(), isStepValid(i) ? Components.VALID : Color.TRANSPARENT);
        }
    }

    private Element buildNavRow(double width) {
        Container row = Components.transparent(width, NAV_ROW_HEIGHT);

        double buttonWidth = 120, buttonHeight = 40;
        ButtonOverlay next = Components.accentButton("Next", 14f, buttonWidth, buttonHeight);
        next.setPosition(width - buttonWidth, (NAV_ROW_HEIGHT - buttonHeight) / 2.0);
        next.onAction(this::advanceStep);
        row.addElement(next);
        return row;
    }

    // Field helpers for the steps

    // Leaves room for the scroll bar.
    protected double fieldWidth(ScrollContainer scroll, double width) {
        double reserve = scroll.getScrollBarStyle().getThickness() + 2 * scroll.getScrollBarStyle().getMargin();
        return Math.max(0, width - reserve);
    }

    // A label with the field under it. Returns the y for the next one.
    protected double placeField(ScrollContainer scroll, String label, Element field, double y, double width, double fieldHeight) {
        TextOverlay labelText = new TextOverlay(label, 13f, theme.getPlaceholderText());
        labelText.setPosition(0, y);
        scroll.addElement(labelText);

        double fieldY = y + labelText.getHeight() + 6;
        field.setPosition(0, fieldY);
        field.setWidth(width);
        field.setHeight(fieldHeight);
        scroll.addElement(field);

        return fieldY + fieldHeight + 18;
    }

    // "Import Card..." with the last error under it.
    protected double placeImportButton(ScrollContainer scroll, double y, double width, Runnable onImport) {
        double buttonWidth = Math.min(180, width);
        ButtonOverlay importButton = Components.secondaryButton("Import Card...", 13f, buttonWidth, 36);
        importButton.getStyling().setBorderThickness(1f);
        importButton.getStyling().setBorderColor(theme.getContainerBorder());
        importButton.setPosition(0, y);
        importButton.onAction(onImport);
        scroll.addElement(importButton);
        double next = y + 36 + 10;

        if (error != null) {
            next = placeError(scroll, next) + 8;
        }
        return next + 8;
    }

    protected double placeError(ScrollContainer scroll, double y) {
        TextOverlay errorText = new TextOverlay(error, 12f, Color.CRIMSON);
        errorText.setPosition(0, y);
        scroll.addElement(errorText);
        return y + errorText.getHeight();
    }

    // A thumbnail of the picked icon (or a placeholder) with Choose/Change and Remove.
    protected double placeIconField(ScrollContainer scroll, double y, double width) {
        return placeIconField(scroll, y, width, pendingIconFile, path -> pendingIconFile = path);
    }

    // For a second icon on the same form, like the character's user. Null means none is picked.
    protected double placeIconField(ScrollContainer scroll, double y, double width, Path icon, Consumer<Path> setIcon) {
        TextOverlay labelText = new TextOverlay("Icon", 13f, theme.getPlaceholderText());
        labelText.setPosition(0, y);
        scroll.addElement(labelText);

        double rowY = y + labelText.getHeight() + 6;
        Container row = Components.transparent(width, ICON_PREVIEW_SIZE);

        Container preview = new Container(ICON_PREVIEW_SIZE, ICON_PREVIEW_SIZE);
        preview.getStyling().setCornerRadius(14f);
        preview.getStyling().setBorderThickness(1f);
        preview.getStyling().setBorderColor(theme.getContainerBorder());
        if (icon != null) {
            Components.fill(preview, Color.TRANSPARENT);
            ImageOverlay image = new ImageOverlay(icon.toString(), ICON_PREVIEW_SIZE, ICON_PREVIEW_SIZE);
            image.getStyling().setCornerRadius(14f);
            preview.addElement(image);
        } else {
            Components.fill(preview, theme.getFieldBackground());
            IconOverlay placeholder = new IconOverlay(Feather.IMAGE, 28, 28);
            placeholder.setTint(theme.getPlaceholderText());
            placeholder.setPosition((ICON_PREVIEW_SIZE - 28) / 2.0, (ICON_PREVIEW_SIZE - 28) / 2.0);
            preview.addElement(placeholder);
        }
        row.addElement(preview);

        double buttonX = ICON_PREVIEW_SIZE + 16;
        double buttonWidth = Math.max(0, Math.min(160, width - buttonX));

        ButtonOverlay choose = Components.secondaryButton(icon == null ? "Choose Image" : "Change Image", 13f, buttonWidth, 36);
        choose.setTextColor(Color.WHITE);
        choose.onAction(() -> pickIconFile(setIcon));

        if (icon != null) {
            choose.setPosition(buttonX, ICON_PREVIEW_SIZE / 2.0 - 36 - 4);
            row.addElement(choose);

            ButtonOverlay remove = Components.textButton("Remove", buttonWidth, 32);
            remove.getStyling().setBorderThickness(1f);
            remove.getStyling().setBorderColor(theme.getButtonBorder());
            remove.getStyling().setCornerRadius(16f);
            remove.setPosition(buttonX, ICON_PREVIEW_SIZE / 2.0 + 4);
            remove.onAction(() -> {
                setIcon.accept(null);
                owner.renderContent();
            });
            row.addElement(remove);
        } else {
            choose.setPosition(buttonX, (ICON_PREVIEW_SIZE - 36) / 2.0);
            row.addElement(choose);
        }

        row.setPosition(0, rowY);
        scroll.addElement(row);
        return rowY + ICON_PREVIEW_SIZE + 18;
    }

    private void pickIconFile(Consumer<Path> setIcon) {
        FileDialog.open()
                .filter("Images", "png", "jpg", "jpeg", "webp", "bmp", "gif")
                .onSelect(path -> {
                    setIcon.accept(path);
                    owner.renderContent();
                })
                .show();
    }

    // Id helpers

    // A slug of the name, with -2, -3... added until it's free.
    protected static String uniqueId(String displayName, Predicate<String> exists) {
        String base = slugify(displayName);
        if (base.isEmpty()) base = "untitled";
        String id = base;
        int suffix = 2;
        while (exists.test(id)) {
            id = base + "-" + suffix++;
        }
        return id;
    }

    protected static String slugify(String input) {
        return input.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }
}
