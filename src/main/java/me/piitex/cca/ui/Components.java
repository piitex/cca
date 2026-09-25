package me.piitex.cca.ui;

import me.piitex.cca.App;
import me.piitex.cca.theme.Palette;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ModalContainer;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.StackLayout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

// The buttons, badges and dialogs every menu uses, so they all look the same.
// Colors are read from the theme every time something is built so a theme change shows on the next redraw.
public final class Components {

    // Icon colors for the small round buttons (edit, copy, regenerate, delete) and their hover wash.
    public static final Color CONTROL_EDIT = new Color(0.45f, 0.62f, 0.95f, 1f);
    public static final Color CONTROL_EDIT_HOVER = new Color(0.45f, 0.62f, 0.95f, 0.20f);
    public static final Color CONTROL_COPY = new Color(0.35f, 0.75f, 0.65f, 1f);
    public static final Color CONTROL_COPY_HOVER = new Color(0.35f, 0.75f, 0.65f, 0.20f);
    public static final Color CONTROL_REGEN = new Color(0.72f, 0.55f, 0.95f, 1f);
    public static final Color CONTROL_REGEN_HOVER = new Color(0.72f, 0.55f, 0.95f, 0.20f);
    public static final Color CONTROL_CONTINUE = new Color(0.95f, 0.72f, 0.35f, 1f);
    public static final Color CONTROL_CONTINUE_HOVER = new Color(0.95f, 0.72f, 0.35f, 0.20f);
    public static final Color CONTROL_DANGER = new Color(0.85f, 0.35f, 0.35f, 1f);
    public static final Color CONTROL_DANGER_HOVER = new Color(0.85f, 0.35f, 0.35f, 0.22f);

    // Filled delete buttons.
    public static final Color DANGER = new Color(0.80f, 0.28f, 0.28f, 1f);
    public static final Color DANGER_HOVER = new Color(0.88f, 0.34f, 0.34f, 1f);

    public static final Color VALID = new Color(0.30f, 0.78f, 0.45f, 1f);
    public static final Color WARN = new Color(0.95f, 0.70f, 0.25f, 1f);

    public static final double CONTROL_SIZE = 32;

    private Components() {
    }

    public static Theme theme() {
        return ThemeManager.getCurrent();
    }

    // Buttons

    // The main action on a screen. Filled with the accent.
    public static ButtonOverlay accentButton(String text, float fontSize, double width, double height) {
        return filledButton(text, fontSize, width, height, Palette.accent(), Palette.accentHover());
    }

    public static ButtonOverlay dangerButton(String text, float fontSize, double width, double height) {
        return filledButton(text, fontSize, width, height, DANGER, DANGER_HOVER);
    }

    public static ButtonOverlay filledButton(String text, float fontSize, double width, double height, Color color, Color hover) {
        ButtonOverlay button = new ButtonOverlay(text, fontSize, Color.WHITE, width, height);
        button.setTextColor(Color.WHITE);
        button.getStyling().setBackgroundColor(color);
        button.getStyling().setHoverColor(hover);
        button.getStyling().setBorderThickness(0f);
        button.getStyling().setCornerRadius((float) (height / 2));
        return button;
    }

    // A normal button in the theme's button color.
    public static ButtonOverlay secondaryButton(String text, float fontSize, double width, double height) {
        Theme theme = theme();
        ButtonOverlay button = new ButtonOverlay(text, fontSize, theme.getText(), width, height);
        button.setTextColor(theme.getText());
        button.getStyling().setBackgroundColor(theme.getButtonBackground());
        button.getStyling().setHoverColor(theme.getButtonHoverBackground());
        button.getStyling().setBorderThickness(0f);
        button.getStyling().setCornerRadius((float) (height / 2));
        return button;
    }

    // Just muted text until it's hovered. Used for the quieter header actions like Back.
    public static ButtonOverlay textButton(String text, double width, double height) {
        Theme theme = theme();
        ButtonOverlay button = new ButtonOverlay(text, 13f, theme.getPlaceholderText(), width, height);
        button.setTextColor(theme.getPlaceholderText());
        button.getStyling().setBackgroundColor(Color.TRANSPARENT);
        button.getStyling().setHoverColor(theme.getButtonHoverBackground());
        return button;
    }

    // A small round icon button. The icon needs its own click handler too, clicks on a child don't reach the parent.
    // Every icon button has a tooltip since there's no label to say what it does. Null is for a disabled one.
    public static Container iconButton(IconAsset icon, String tooltip, Color tint, Color hover, Runnable action) {
        return iconButton(icon, tooltip, CONTROL_SIZE, 16, (float) (CONTROL_SIZE / 2), tint, hover, action);
    }

    public static Container iconButton(IconAsset icon, String tooltip, double size, double iconSize, float radius, Color tint, Color hover, Runnable action) {
        Container button = new Container(size, size);
        if (tooltip != null) button.setTooltip(tooltip);
        button.getStyling().setBackgroundColor(Color.TRANSPARENT);
        button.getStyling().setHoverColor(hover);
        button.getStyling().setBorderThickness(0f);
        button.getStyling().setCornerRadius(radius);
        button.setCursorMode(CursorMode.POINTER);
        button.onMouseClick(event -> action.run());

        IconOverlay iconOverlay = new IconOverlay(icon, iconSize, iconSize);
        iconOverlay.setTint(tint);
        iconOverlay.setPosition((size - iconSize) / 2.0, (size - iconSize) / 2.0);
        iconOverlay.setCursorMode(CursorMode.POINTER);
        iconOverlay.onMouseClick(event -> action.run());
        button.addElement(iconOverlay);
        return button;
    }

    // A round filled button with an icon in the middle, like the send button.
    public static Container roundButton(IconAsset icon, String tooltip, double size, double iconSize, Color color, Color hover, Runnable action) {
        Container button = iconButton(icon, tooltip, size, iconSize, (float) (size / 2), Color.WHITE, hover, action);
        button.getStyling().setBackgroundColor(color);
        return button;
    }

    // Containers

    // Nothing drawn, just holds and positions other elements.
    public static Container transparent(double width, double height) {
        Container container = new Container(width, height);
        clearStyle(container);
        return container;
    }

    public static void clearStyle(Element element) {
        element.getStyling().setBackgroundColor(Color.TRANSPARENT);
        element.getStyling().setHoverColor(Color.TRANSPARENT);
        element.getStyling().setBorderThickness(0f);
    }

    public static final float PANEL_RADIUS = 20f;

    // The see through panel the chat, creators and settings are drawn in.
    public static Container panel(double width, double height) {
        Container panel = new Container(width, height);
        panel.useTheme();
        Color background = Palette.panel(0.92f);
        panel.getStyling().setBackgroundColor(background);
        panel.getStyling().setHoverColor(background);
        panel.getStyling().setCornerRadius(PANEL_RADIUS);
        return panel;
    }

    public static void fill(Element element, Color color) {
        element.getStyling().setBackgroundColor(color);
        element.getStyling().setHoverColor(color);
    }

    public static void clear(Container container) {
        for (Element child : List.copyOf(container.getElements().values())) {
            container.removeElement(child);
        }
    }

    // The round accent gradient badge with a white icon. Only used for small marks so it doesn't get noisy.
    public static StackLayout gradientBadge(double size, IconAsset icon, double iconSize) {
        StackLayout badge = new StackLayout(size, size);
        badge.setAlignment(Layout.Alignment.CENTER);
        badge.getStyling().setBackgroundColor(new LinearGradient(Palette.accentStart(), Palette.accentEnd()));
        badge.getStyling().setCornerRadius((float) (size / 2));
        IconOverlay iconOverlay = new IconOverlay(icon, iconSize, iconSize);
        iconOverlay.setTint(Color.WHITE);
        badge.addElement(iconOverlay);
        return badge;
    }

    // Badge, title and a hint centered in the space. Used when there's nothing to show yet.
    public static VerticalLayout emptyState(double width, double height, IconAsset icon, String title, String hint) {
        VerticalLayout box = new VerticalLayout(width, height);
        box.setAlignment(Layout.Alignment.CENTER);
        box.setSpacing(12f);
        clearStyle(box);

        box.addElement(gradientBadge(56, icon, 24));
        box.addElement(new TextOverlay(title, 15f, theme().getText()));
        box.addElement(new TextOverlay(hint, 13f, theme().getPlaceholderText()));
        return box;
    }

    // Cuts the text down with "..." until it fits.
    public static TextOverlay fitText(String text, float fontSize, Color color, double maxWidth) {
        TextOverlay overlay = new TextOverlay(text, fontSize, color);
        String label = text;
        while (overlay.getWidth() > maxWidth && label.length() > 1) {
            label = label.substring(0, label.length() - 1);
            overlay = new TextOverlay(label.stripTrailing() + "...", fontSize, color);
        }
        return overlay;
    }

    // Colors

    // A color for a character without an icon. Worked out from the id so it never has to be saved.
    public static Color accentColorFor(String id) {
        float hue = (Math.abs(id.hashCode()) % 360) / 360f;
        return hsvToRgb(hue, 0.55f, 0.85f);
    }

    private static Color hsvToRgb(float h, float s, float v) {
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        return switch (i % 6) {
            case 0 -> new Color(v, t, p, 1f);
            case 1 -> new Color(q, v, p, 1f);
            case 2 -> new Color(p, v, t, 1f);
            case 3 -> new Color(p, q, v, 1f);
            case 4 -> new Color(t, p, v, 1f);
            default -> new Color(v, p, q, 1f);
        };
    }

    // Dialogs

    // Asks before deleting something that can't be undone. onConfirm runs after the dialog closes.
    public static void confirmDelete(String title, String body, Runnable onConfirm) {
        confirmDelete(title, body, "Delete", onConfirm);
    }

    public static void confirmDelete(String title, String body, String confirmLabel, Runnable onConfirm) {
        confirmDelete(title, body, confirmLabel, onConfirm, null);
    }

    // onDismiss runs when the dialog closes without confirming (Cancel, Escape or a click outside).
    public static void confirmDelete(String title, String body, Runnable onConfirm, Runnable onDismiss) {
        confirmDelete(title, body, "Delete", onConfirm, onDismiss);
    }

    private static void confirmDelete(String title, String body, String confirmLabel, Runnable onConfirm, Runnable onDismiss) {
        confirmDelete(title, body, List.of(), confirmLabel, ticked -> onConfirm.run(), onDismiss);
    }

    // Same dialog with a check box per option under the text. onConfirm gets which ones were ticked, in order.
    public static void confirmDelete(String title, String body, List<String> options, Consumer<boolean[]> onConfirm) {
        confirmDelete(title, body, options, "Delete", onConfirm, null);
    }

    public static void confirmDelete(String title, String body, List<String> options, Consumer<boolean[]> onConfirm, Runnable onDismiss) {
        confirmDelete(title, body, options, "Delete", onConfirm, onDismiss);
    }

    private static void confirmDelete(String title, String body, List<String> options, String confirmLabel,
                                      Consumer<boolean[]> onConfirm, Runnable onDismiss) {
        double width = 340, inner = width - 48;
        VerticalLayout panel = dialogPanel(width, 190 + 34 * options.size());
        panel.addElement(new TextOverlay(title, 16f, theme().getText()));
        panel.addElement(new TextFlowOverlay(body, inner, 13f, theme().getPlaceholderText()));

        List<CheckBoxOverlay> checks = new ArrayList<>();
        for (String option : options) {
            CheckBoxOverlay check = new CheckBoxOverlay(20);
            checks.add(check);
            HorizontalLayout row = new HorizontalLayout(inner, 24);
            row.setSpacing(10f);
            clearStyle(row);
            row.addElement(check);
            TextOverlay label = new TextOverlay(option, 13f, theme().getText());
            label.setCursorMode(CursorMode.POINTER);
            label.onMouseClick(event -> check.toggle());
            row.addElement(label);
            panel.addElement(row);
        }

        ModalContainer modal = new ModalContainer(panel);
        boolean[] confirmed = new boolean[1];
        if (onDismiss != null) {
            modal.onClose(() -> {
                if (!confirmed[0]) onDismiss.run();
            });
        }
        ButtonOverlay confirm = dangerButton(confirmLabel, 13f, (inner - 12) / 2, 38);
        confirm.onAction(() -> {
            confirmed[0] = true;
            modal.close();
            boolean[] ticked = new boolean[checks.size()];
            for (int i = 0; i < ticked.length; i++) ticked[i] = checks.get(i).isChecked();
            onConfirm.accept(ticked);
        });
        panel.addElement(dialogButtons(inner, modal, confirm));
        modal.show(App.window);
    }

    /**
     * Asks for a line of text. onConfirm gets it trimmed.
     *
     * @param allowBlank if false the dialog won't close until something is typed
     */
    public static void promptText(String title, String placeholder, String initial, String confirmLabel,
                                  int maxLength, boolean allowBlank, Consumer<String> onConfirm) {
        promptText(title, placeholder, initial, confirmLabel, maxLength, allowBlank, onConfirm, null);
    }

    // onDismiss runs when the dialog closes without confirming (Cancel, Escape or a click outside).
    public static void promptText(String title, String placeholder, String initial, String confirmLabel,
                                  int maxLength, boolean allowBlank, Consumer<String> onConfirm, Runnable onDismiss) {
        double width = 360, inner = width - 48;
        VerticalLayout panel = dialogPanel(width, 200);
        panel.addElement(new TextOverlay(title, 16f, theme().getText()));

        TextFieldOverlay field = new TextFieldOverlay(inner, 38);
        field.setPlaceholder(placeholder);
        if (maxLength > 0) field.setMaxLength(maxLength);
        field.setText(initial);
        panel.addElement(field);

        ModalContainer modal = new ModalContainer(panel);
        boolean[] confirmed = new boolean[1];
        if (onDismiss != null) {
            modal.onClose(() -> {
                if (!confirmed[0]) onDismiss.run();
            });
        }
        Runnable submit = () -> {
            String text = field.getText().trim();
            if (text.isEmpty() && !allowBlank) return;
            confirmed[0] = true;
            modal.close();
            onConfirm.accept(text);
        };
        field.onSubmit(text -> submit.run());

        ButtonOverlay confirm = accentButton(confirmLabel, 13f, (inner - 12) / 2, 38);
        confirm.onAction(submit);
        panel.addElement(dialogButtons(inner, modal, confirm));
        modal.show(App.window);
        Scheduler.runLater(() -> App.window.requestFocus(field));
    }

    /**
     * Asks for one or more passwords. {@code check} gets what was typed and returns what's wrong with it, or null to
     * accept. It runs off the render thread since unlocking a key is slow on purpose. onDone runs after the dialog closes.
     */
    public static void promptPasswords(String title, String body, List<String> placeholders, String confirmLabel,
                                       Function<List<String>, String> check, Runnable onDone) {
        double width = 380, inner = width - 48;
        VerticalLayout panel = dialogPanel(width, 200 + 52 * placeholders.size());
        panel.addElement(new TextOverlay(title, 16f, theme().getText()));
        panel.addElement(new TextFlowOverlay(body, inner, 13f, theme().getPlaceholderText()));

        List<TextFieldOverlay> fields = new ArrayList<>();
        for (String placeholder : placeholders) {
            TextFieldOverlay field = new TextFieldOverlay(inner, 38);
            field.setPlaceholder(placeholder);
            field.setPassword(true);
            fields.add(field);
            panel.addElement(field);
        }
        TextOverlay problem = new TextOverlay(" ", 12f, WARN);
        panel.addElement(problem);

        ModalContainer modal = new ModalContainer(panel);
        boolean[] busy = new boolean[1];
        Runnable submit = () -> {
            if (busy[0]) return;
            busy[0] = true;
            List<String> values = fields.stream().map(TextFieldOverlay::getText).toList();
            Thread.ofVirtual().name("password-check").start(() -> {
                String result;
                try {
                    result = check.apply(values);
                } catch (RuntimeException e) {
                    App.logger.error("Password check failed", e);
                    result = "Something went wrong: " + e.getMessage();
                }
                String message = result;
                Scheduler.runLater(() -> {
                    busy[0] = false;
                    if (message == null) {
                        modal.close();
                        if (onDone != null) onDone.run();
                    } else {
                        problem.setText(message);
                        problem.setTextColor(WARN);
                    }
                });
            });
        };
        for (TextFieldOverlay field : fields) field.onSubmit(text -> submit.run());

        ButtonOverlay confirm = accentButton(confirmLabel, 13f, (inner - 12) / 2, 38);
        confirm.onAction(submit);
        panel.addElement(dialogButtons(inner, modal, confirm));
        modal.show(App.window);
        Scheduler.runLater(() -> App.window.requestFocus(fields.get(0)));
    }

    public static VerticalLayout dialogPanel(double width, double height) {
        VerticalLayout panel = new VerticalLayout(width, height);
        panel.useTheme();
        panel.setPadding(24f);
        panel.setSpacing(14f);
        panel.setAlignment(Layout.Alignment.TOP_CENTER);
        return panel;
    }

    // Cancel on the left, the confirm button on the right.
    public static HorizontalLayout dialogButtons(double width, ModalContainer modal, ButtonOverlay confirm) {
        HorizontalLayout buttons = new HorizontalLayout(width, 40);
        buttons.setSpacing(12f);
        clearStyle(buttons);

        ButtonOverlay cancel = secondaryButton("Cancel", 13f, confirm.getWidth(), 38);
        cancel.onAction(modal::close);
        buttons.addElement(cancel);
        buttons.addElement(confirm);
        return buttons;
    }
}
