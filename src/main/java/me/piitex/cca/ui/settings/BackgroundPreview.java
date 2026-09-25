package me.piitex.cca.ui.settings;

import me.piitex.cca.App;
import me.piitex.cca.background.Backgrounds;
import me.piitex.cca.background.Backgrounds.Type;
import me.piitex.cca.background.ImageBackground;
import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.chat.ChatPreview;
import me.piitex.engine.ui.background.Background;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.theme.Theme;

// A small window shaped box running the real background with a bit of sidebar and a message card on top,
// so it can be judged the way it'll actually look behind the panels. show() updates it in place.
final class BackgroundPreview {
    // The column beside the options, every tab's preview is this wide and as tall as the tab.
    static final double WIDTH = ChatPreview.WIDTH;

    private static final double INSET = 10;
    private static final double SIDEBAR_WIDTH = 96;

    private final Container root;
    private final double width;
    private final double height;
    private final double areaScale;
    private Container mock;
    private Type shownType;
    private Background effect;

    BackgroundPreview(double height) {
        this.width = WIDTH;
        this.height = height;
        root = new Container(WIDTH, height);
        root.getStyling().setBorderThickness(1f);
        root.getStyling().setBorderColor(Components.theme().getContainerBorder());
        root.getStyling().setCornerRadius(8f);

        // Scale particle counts by area so the preview looks as dense as the real window.
        double windowArea = Math.max(1, App.instance.getAppConfig().getWidth() * (double) App.instance.getAppConfig().getHeight());
        areaScale = Math.min(1.0, width * height / windowArea);
    }

    Container element() {
        return root;
    }

    // The effect being previewed, null for gradient and solid.
    Background effect() {
        return effect;
    }

    // themeWindow is shown under the effect, it's the window color of the theme that will be selected.
    void show(AppearanceSettings settings, Color themeWindow) {
        Type type = Type.of(settings.backgroundType.get());
        if (type != shownType) {
            shownType = type;
            effect = Backgrounds.createEffect(type);
            root.setBackground(effect);
            // A picture is laid out for the window's shape so it's previewed in a box that shape.
            double windowWidth = App.window.getWindowOptions().getWidth();
            double windowHeight = App.window.getWindowOptions().getHeight();
            double aspect = windowWidth / Math.max(1, windowHeight);
            double boxWidth = type == Type.IMAGE ? Math.min(width, height * aspect) : width;
            double boxHeight = type == Type.IMAGE ? Math.min(height, boxWidth / aspect) : height;
            buildMock((width - boxWidth) / 2.0, (height - boxHeight) / 2.0, boxWidth, boxHeight);
        }
        if (effect != null) Backgrounds.configure(effect, settings, areaScale);
        if (effect instanceof ImageBackground image) {
            image.setReferenceSize((float) App.window.getWindowOptions().getWidth(), (float) App.window.getWindowOptions().getHeight());
        }

        Paint fill = Backgrounds.fill(settings);
        if (fill == null) fill = themeWindow;
        root.getStyling().setBackgroundColor(fill);
        root.getStyling().setHoverColor(fill);
    }

    // A sidebar and a message card drawn over the background.
    private void buildMock(double x, double y, double mockWidth, double mockHeight) {
        if (mock != null) root.removeElement(mock);
        Theme theme = Components.theme();
        mock = surface(mockWidth, mockHeight, Color.TRANSPARENT);
        mock.setPosition(x, y);
        root.addElement(mock);

        double inner = mockHeight - 2 * INSET;
        Container sidebar = surface(SIDEBAR_WIDTH, inner, Palette.panel(0.92f));
        sidebar.setPosition(INSET, INSET);
        mock.addElement(sidebar);
        Container active = surface(SIDEBAR_WIDTH - 16, 26, Palette.tint());
        active.setPosition(8, 10);
        sidebar.addElement(active);
        TextOverlay activeText = new TextOverlay("Characters", 12f, Palette.accent());
        activeText.setPosition(10, (26 - activeText.getHeight()) / 2.0);
        active.addElement(activeText);
        TextOverlay idle = new TextOverlay("Models", 12f, theme.getPlaceholderText());
        idle.setPosition(18, 10 + 26 + 8 + (26 - idle.getHeight()) / 2.0);
        sidebar.addElement(idle);

        double cardWidth = Math.min(210, Math.max(96, mockWidth - SIDEBAR_WIDTH - 3 * INSET));
        Container card = surface(cardWidth, 44, Palette.panel(0.92f));
        card.setPosition(mockWidth - INSET - cardWidth, mockHeight - INSET - 44);
        card.getStyling().setBorderThickness(1f);
        card.getStyling().setBorderColor(theme.getContainerBorder());
        mock.addElement(card);
        TextOverlay message = new TextOverlay(cardWidth >= 170 ? "Panels sit over the background" : "A message", 12f, theme.getText());
        message.setPosition(12, (44 - message.getHeight()) / 2.0);
        card.addElement(message);
    }

    private static Container surface(double width, double height, Color color) {
        Container c = new Container(width, height);
        Components.fill(c, color);
        c.getStyling().setBorderThickness(0f);
        c.getStyling().setCornerRadius(8f);
        return c;
    }
}
