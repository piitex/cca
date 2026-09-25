package me.piitex.cca.ui.settings;

import me.piitex.cca.theme.AppTheme;
import me.piitex.cca.theme.AppTheme.Slot;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.TextOverlay;

// A tiny version of the app (sidebar, panel, bubble, buttons) in a theme's colors. show() restyles it
// in place so the color picker keeps its drag while the preview follows.
final class ThemePreview {
    static final double HEIGHT = 124;

    private static final double INSET = 10;
    private static final double SIDEBAR_WIDTH = 132;

    private final Container root;
    private final Container sidebar, navActive, panel, bubble, accentButton, plainButton, badge;
    private final TextOverlay navActiveText, navIdleText, title, bubbleText, muted, accentText, plainText;

    ThemePreview(double width) {
        root = new Container(width, HEIGHT);
        root.getStyling().setBorderThickness(1f);

        double inner = HEIGHT - 2 * INSET;
        sidebar = surface(SIDEBAR_WIDTH, inner);
        sidebar.setPosition(INSET, INSET);
        root.addElement(sidebar);

        navActive = surface(SIDEBAR_WIDTH - 16, 30);
        navActive.setPosition(8, 10);
        sidebar.addElement(navActive);
        navActiveText = new TextOverlay("Characters", 13f, Color.WHITE);
        navActiveText.setPosition(12, (30 - navActiveText.getHeight()) / 2.0);
        navActive.addElement(navActiveText);

        navIdleText = new TextOverlay("Models", 13f, Color.WHITE);
        navIdleText.setPosition(20, 10 + 30 + 10 + (30 - navIdleText.getHeight()) / 2.0);
        sidebar.addElement(navIdleText);

        double panelX = INSET + SIDEBAR_WIDTH + INSET;
        double panelWidth = Math.max(0, width - panelX - INSET);
        panel = surface(panelWidth, inner);
        panel.setPosition(panelX, INSET);
        root.addElement(panel);

        title = new TextOverlay("Preview", 15f, Color.WHITE);
        title.setPosition(16, 12);
        panel.addElement(title);

        bubble = surface(190, 30);
        bubble.setPosition(16, 44);
        panel.addElement(bubble);
        bubbleText = new TextOverlay("A message in a bubble", 12f, Color.WHITE);
        bubbleText.setPosition(12, (30 - bubbleText.getHeight()) / 2.0);
        bubble.addElement(bubbleText);

        muted = new TextOverlay("Muted text and hints", 12f, Color.WHITE);
        muted.setPosition(16, 44 + 30 + 10);
        panel.addElement(muted);

        double buttonWidth = 104;
        accentButton = surface(buttonWidth, 30);
        accentButton.setPosition(panelWidth - 16 - buttonWidth, 14);
        panel.addElement(accentButton);
        accentText = new TextOverlay("Accent", 13f, Color.WHITE);
        accentText.setPosition((buttonWidth - accentText.getWidth()) / 2.0, (30 - accentText.getHeight()) / 2.0);
        accentButton.addElement(accentText);

        plainButton = surface(buttonWidth, 30);
        plainButton.setPosition(panelWidth - 16 - buttonWidth, 54);
        panel.addElement(plainButton);
        plainText = new TextOverlay("Button", 13f, Color.WHITE);
        plainText.setPosition((buttonWidth - plainText.getWidth()) / 2.0, (30 - plainText.getHeight()) / 2.0);
        plainButton.addElement(plainText);

        badge = surface(24, 24);
        badge.setPosition(panelWidth - 16 - buttonWidth - 12 - 24, 17);
        panel.addElement(badge);
    }

    Container element() {
        return root;
    }

    private static Container surface(double width, double height) {
        Container c = new Container(width, height);
        c.getStyling().setBorderThickness(0f);
        return c;
    }

    void show(AppTheme theme) {
        float r = theme.cornerRadiusValue();
        Color text = theme.get(Slot.TEXT);
        Color line = theme.get(Slot.BORDER);

        Components.fill(root, theme.get(Slot.WINDOW));
        root.getStyling().setBorderColor(line);
        root.getStyling().setCornerRadius(Math.max(r, 8f));

        Components.fill(sidebar, theme.get(Slot.PANEL));
        sidebar.getStyling().setCornerRadius(r);
        Components.fill(panel, theme.get(Slot.PANEL));
        panel.getStyling().setCornerRadius(r);

        Components.fill(navActive, theme.tint());
        navActive.getStyling().setCornerRadius(r);
        navActiveText.setTextColor(theme.accent());
        navIdleText.setTextColor(theme.get(Slot.MUTED));

        title.setTextColor(text);
        Components.fill(bubble, theme.get(Slot.FIELD));
        bubble.getStyling().setBorderThickness(1f);
        bubble.getStyling().setBorderColor(line);
        bubble.getStyling().setCornerRadius(Math.max(r, 6f) + 6f);
        bubbleText.setTextColor(text);
        muted.setTextColor(theme.get(Slot.MUTED));

        Components.fill(accentButton, theme.accent());
        accentButton.getStyling().setCornerRadius(15f);
        accentText.setTextColor(Color.WHITE);
        Components.fill(plainButton, theme.get(Slot.BUTTON));
        plainButton.getStyling().setCornerRadius(15f);
        plainText.setTextColor(text);

        LinearGradient gradient = new LinearGradient(theme.accent(), theme.accentEnd());
        badge.getStyling().setBackgroundColor(gradient);
        badge.getStyling().setHoverColor(gradient);
        badge.getStyling().setCornerRadius(12f);
    }
}
