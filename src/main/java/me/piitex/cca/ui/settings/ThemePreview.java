package me.piitex.cca.ui.settings;

import me.piitex.cca.theme.AppTheme;
import me.piitex.cca.theme.AppTheme.Slot;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.chat.ChatPreview;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.ui.scroll.ScrollContainer;
import me.piitex.engine.ui.theme.Themeable;

import java.util.ArrayList;
import java.util.List;

// A tiny version of the app in a theme's colors, in a column beside the options. The controls are the engine's own, so they
// hover, click and type like the real ones. show() restyles everything in place so the color picker keeps its drag while
// the preview follows, and what was typed or clicked stays.
final class ThemePreview {
    // The column beside the options, every tab's preview is this wide and as tall as the tab.
    static final double WIDTH = ChatPreview.WIDTH;

    private static final double INSET = 10;
    private static final double SIDEBAR_WIDTH = 96;
    private static final double PADDING = 12;
    private static final double ROW = 34;
    private static final double GAP = 10;
    private static final String[] NAV = {"Characters", "Models", "Settings"};

    private final Container root, sidebar, badge, bubble, card;
    private final Container[] nav = new Container[NAV.length];
    private final TextOverlay[] navText = new TextOverlay[NAV.length];
    private final ScrollContainer panel;
    private final TextOverlay title, bubbleText, cardTitle, cardHint, toggleText, checkText, muted;
    private final TextFieldOverlay field;
    private final DropdownOverlay<String> dropdown;
    private final ToggleSwitchOverlay toggle;
    private final CheckBoxOverlay check;
    private final SliderOverlay slider;
    private final ButtonOverlay accentButton, plainButton;
    // The engine's own controls, themed by handing them the theme being edited.
    private final List<Themeable> themed = new ArrayList<>();
    // The ones with a tooltip, it's drawn in the colors of the theme being edited.
    private final List<Element> tooltips = new ArrayList<>();

    private AppTheme theme;
    private int active;
    private boolean cardSelected;
    private int clicks;

    ThemePreview(double height) {
        root = new Container(WIDTH, height);
        root.getStyling().setBorderThickness(1f);

        double inner = height - 2 * INSET;
        sidebar = surface(SIDEBAR_WIDTH, inner);
        sidebar.setPosition(INSET, INSET);
        root.addElement(sidebar);
        for (int i = 0; i < NAV.length; i++) {
            int index = i;
            nav[i] = surface(SIDEBAR_WIDTH - 16, 30);
            nav[i].setPosition(8, 10 + i * 36);
            SettingsUi.clickable(nav[i], () -> {
                active = index;
                styleNav();
            });
            navText[i] = new TextOverlay(NAV[i], 12f, Color.WHITE);
            navText[i].setPosition(10, (30 - navText[i].getHeight()) / 2.0);
            SettingsUi.clickable(navText[i], () -> {
                active = index;
                styleNav();
            });
            nav[i].addElement(navText[i]);
            sidebar.addElement(nav[i]);
        }

        double panelX = INSET + SIDEBAR_WIDTH + INSET;
        double panelWidth = Math.max(0, WIDTH - panelX - INSET);
        panel = new ScrollContainer(panelWidth, inner);
        panel.setPosition(panelX, INSET);
        panel.getStyling().setBorderThickness(0f);
        root.addElement(panel);

        // Room on the right for the scroll bar.
        double width = Math.max(0, panelWidth - 2 * PADDING - panel.getScrollBarStyle().getThickness() - 2 * panel.getScrollBarStyle().getMargin());
        double y = PADDING;

        title = new TextOverlay("Preview", 15f, Color.WHITE);
        title.setPosition(PADDING, y + (24 - title.getHeight()) / 2.0);
        panel.addElement(title);
        badge = surface(24, 24);
        badge.setPosition(PADDING + width - 24, y);
        badge.getStyling().setCornerRadius(12f);
        panel.addElement(badge);
        tooltips.add(badge);
        badge.setTooltip("The accent gradient");
        y += 24 + GAP;

        bubble = surface(width, ROW);
        bubble.setPosition(PADDING, y);
        bubble.getStyling().setBorderThickness(1f);
        bubbleText = new TextOverlay("A message in a bubble", 12f, Color.WHITE);
        bubbleText.setPosition(12, (ROW - bubbleText.getHeight()) / 2.0);
        bubble.addElement(bubbleText);
        panel.addElement(bubble);
        y += ROW + GAP;

        // Click it to select, like a row in a list.
        double cardHeight = 46;
        card = surface(width, cardHeight);
        card.setPosition(PADDING, y);
        card.getStyling().setBorderThickness(1f);
        Runnable pick = () -> {
            cardSelected = !cardSelected;
            styleCard();
        };
        SettingsUi.clickable(card, pick);
        cardTitle = new TextOverlay("A card or a row", 13f, Color.WHITE);
        cardHint = new TextOverlay("Hover it, click it", 11f, Color.WHITE);
        double block = cardTitle.getHeight() + 2 + cardHint.getHeight();
        cardTitle.setPosition(12, (cardHeight - block) / 2.0);
        cardHint.setPosition(12, (cardHeight - block) / 2.0 + cardTitle.getHeight() + 2);
        SettingsUi.clickable(cardTitle, pick);
        SettingsUi.clickable(cardHint, pick);
        card.addElement(cardTitle);
        card.addElement(cardHint);
        panel.addElement(card);
        tooltips.add(card);
        card.setTooltip("Tooltips use the theme's tooltip colors");
        y += cardHeight + GAP;

        field = new TextFieldOverlay(width, ROW);
        field.setPlaceholder("Type something");
        field.setPosition(PADDING, y);
        panel.addElement(field);
        themed.add(field);
        y += ROW + GAP;

        dropdown = new DropdownOverlay<>(width, ROW);
        dropdown.setFontSize(13f);
        dropdown.setItems(List.of("First option", "Second option", "Third option"));
        dropdown.setSelected("First option");
        dropdown.setPosition(PADDING, y);
        panel.addElement(dropdown);
        themed.add(dropdown);
        y += ROW + GAP;

        toggle = new ToggleSwitchOverlay(44, 24, true);
        toggle.setPosition(PADDING, y + 5);
        panel.addElement(toggle);
        themed.add(toggle);
        toggleText = new TextOverlay("Toggle", 12f, Color.WHITE);
        toggleText.setPosition(PADDING + 44 + 8, y + (ROW - toggleText.getHeight()) / 2.0);
        panel.addElement(toggleText);
        check = new CheckBoxOverlay(22, true);
        check.setPosition(PADDING + width / 2.0 + 4, y + 6);
        panel.addElement(check);
        themed.add(check);
        checkText = new TextOverlay("Checkbox", 12f, Color.WHITE);
        checkText.setPosition(PADDING + width / 2.0 + 4 + 22 + 8, y + (ROW - checkText.getHeight()) / 2.0);
        panel.addElement(checkText);
        y += ROW + GAP;

        slider = new SliderOverlay(width, 0, 100, 40);
        slider.setPosition(PADDING, y);
        panel.addElement(slider);
        themed.add(slider);
        y += Math.max(ROW, slider.getHeight()) + GAP;

        accentButton = new ButtonOverlay("Accent button", 13f, Color.WHITE, width, ROW);
        accentButton.setPosition(PADDING, y);
        accentButton.onAction(() -> {
            clicks++;
            accentButton.setText("Clicked " + clicks + (clicks == 1 ? " time" : " times"));
        });
        panel.addElement(accentButton);
        tooltips.add(accentButton);
        accentButton.setTooltip("Click me");
        y += ROW + GAP;

        plainButton = new ButtonOverlay("Button", 13f, Color.WHITE, width, ROW);
        plainButton.setPosition(PADDING, y);
        plainButton.onAction(() -> plainButton.setText(plainButton.getText().equals("Button") ? "Clicked" : "Button"));
        panel.addElement(plainButton);
        themed.add(plainButton);
        tooltips.add(plainButton);
        plainButton.setTooltip("A plain button\nwith two lines");
        y += ROW + GAP;

        muted = new TextOverlay("Muted text and hints", 12f, Color.WHITE);
        muted.setPosition(PADDING, y);
        panel.addElement(muted);
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
        this.theme = theme;
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
        panel.getScrollBarStyle().setThumbColor(theme.get(Slot.SCROLL_THUMB));
        panel.getScrollBarStyle().setTrackColor(theme.get(Slot.SCROLL_TRACK));

        title.setTextColor(text);
        muted.setTextColor(theme.get(Slot.MUTED));
        toggleText.setTextColor(text);
        checkText.setTextColor(text);
        LinearGradient gradient = new LinearGradient(theme.accent(), theme.accentEnd());
        badge.getStyling().setBackgroundColor(gradient);
        badge.getStyling().setHoverColor(gradient);

        Components.fill(bubble, theme.get(Slot.FIELD));
        bubble.getStyling().setBorderColor(line);
        bubble.getStyling().setCornerRadius(Math.max(r, 6f) + 6f);
        bubbleText.setTextColor(text);

        // The controls take their colors from the theme, the accents and the pill shape are what the app sets on top.
        for (Themeable control : themed) control.applyTheme(theme);
        toggle.setOnColor(theme.accent());
        slider.setFillColor(theme.accent());
        slider.setThumbColor(theme.accent());
        Color hint = theme.get(Slot.MUTED);
        dropdown.setTextColor(text);

        accentButton.setTextColor(Color.WHITE);
        accentButton.getStyling().setBackgroundColor(theme.accent());
        accentButton.getStyling().setHoverColor(theme.accentHover());
        accentButton.getStyling().setBorderThickness(0f);
        accentButton.getStyling().setCornerRadius((float) (ROW / 2));
        plainButton.setTextColor(text);
        plainButton.getStyling().setBorderThickness(0f);
        plainButton.getStyling().setCornerRadius((float) (ROW / 2));
        cardHint.setTextColor(hint);

        for (Element element : tooltips) element.setTooltipTheme(theme);

        styleNav();
        styleCard();
    }

    private void styleNav() {
        if (theme == null) return;
        float r = theme.cornerRadiusValue();
        for (int i = 0; i < nav.length; i++) {
            boolean on = i == active;
            nav[i].getStyling().setBackgroundColor(on ? theme.tint() : Color.TRANSPARENT);
            nav[i].getStyling().setHoverColor(on ? theme.tintStrong() : theme.rowHover());
            nav[i].getStyling().setCornerRadius(r);
            navText[i].setTextColor(on ? theme.accent() : theme.get(Slot.MUTED));
        }
    }

    private void styleCard() {
        if (theme == null) return;
        Color text = theme.get(Slot.TEXT);
        Color base = new Color(text.getR(), text.getG(), text.getB(), 0.045f);
        card.getStyling().setBackgroundColor(cardSelected ? theme.tint() : base);
        card.getStyling().setHoverColor(cardSelected ? theme.tintStrong() : theme.rowHover());
        card.getStyling().setBorderColor(cardSelected ? theme.accent() : theme.get(Slot.BORDER));
        card.getStyling().setCornerRadius(Math.max(theme.cornerRadiusValue(), 6f) + 4f);
        cardTitle.setTextColor(text);
    }
}
