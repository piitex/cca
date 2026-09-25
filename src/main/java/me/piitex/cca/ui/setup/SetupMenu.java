package me.piitex.cca.ui.setup;

import me.piitex.cca.App;
import me.piitex.cca.background.Backgrounds;
import me.piitex.cca.crypto.FileCrypter;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.theme.Themes;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.Window;
import me.piitex.engine.WindowStyle;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.ScrollingGradient;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.StackLayout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.SeparatorOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

public class SetupMenu {
    private final Window window = App.window;

    // Read from the live theme when building, so these screens match the theme the user picked.
    static Color accentStart() {
        return Palette.accentStart();
    }

    static Color accentEnd() {
        return Palette.accentEnd();
    }

    static Color mutedText() {
        return Themes.current().getPlaceholderText();
    }

    static Color divider() {
        return withAlpha(Themes.current().getText(), 0.10f);
    }

    static Color footerText() {
        Color muted = mutedText();
        return withAlpha(muted, 0.8f);
    }

    // The text color at a faint opacity, for outlines and fills that have to work on light and dark themes.
    static Color faint(float alpha) {
        return withAlpha(Themes.current().getText(), alpha);
    }

    private static Color lighter(Color c) {
        return new Color(Math.min(1f, c.getR() + 0.10f), Math.min(1f, c.getG() + 0.10f), Math.min(1f, c.getB() + 0.10f), c.getA());
    }

    private static Color withAlpha(Color color, float alpha) {
        return new Color(color.getR(), color.getG(), color.getB(), alpha);
    }

    public SetupMenu() {
        createWelcomeMenu();
    }

    public void createWelcomeMenu() {
        window.setSize(800, 600);
        window.setStyle(WindowStyle.BORDERLESS);
        applyBackground(window);

        // A plain Container and not a Layout. A Layout re-aligns its children every frame
        // which would snap the card back to the center while it's being dragged.
        Container main = new Container(window.getWindowOptions().getWidth(), window.getWindowOptions().getHeight());
        window.addContainer(main);

        double padding = 40;
        double cardWidth = 420, cardHeight = 380;
        Container card = createCard(main, cardWidth, cardHeight);

        VerticalLayout content = new VerticalLayout(340, 260);
        content.setSpacing(20f);
        content.setAlignment(Layout.Alignment.TOP_CENTER);
        content.setPosition(padding, padding);
        card.addElement(content);

        content.addElement(gradientBadge(64, Feather.USERS, 28));

        TextOverlay title = new TextOverlay("Welcome to CCA");
        title.setFontSize(32f);
        title.setTextColor(new ScrollingGradient(accentStart(), accentEnd(), 40f));
        content.addElement(title);

        TextOverlay subtitle = new TextOverlay("Let's set up your device.", 15f, mutedText());
        content.addElement(subtitle);

        SeparatorOverlay divider = new SeparatorOverlay(340);
        divider.setLineColor(divider());
        content.addElement(divider);

        ButtonOverlay getStarted = gradientButton("Get Started", 16f, 340, 52);
        getStarted.onAction(() -> card.fadeOut(0.3f, () -> {
            window.removeContainer(main);
            new TosMenu();
        }));
        content.addElement(getStarted);

        addFooter(card, cardWidth, cardHeight);
        card.fadeIn(1f);
    }

    // The last step of both setup routes, local and cloud. Setup counts as done from here, the password step after it
    // is optional and can be left for Settings.
    static void finishSetup(Container card, Container main) {
        App.instance.getAppConfig().setSetupComplete(true);
        card.fadeOut(0.3f, () -> {
            App.window.removeContainer(main);
            if (FileCrypter.isPasswordProtected(App.environment)) new MainMenu();
            else new PasswordSetupMenu();
        });
    }

    // The background the user picked in Settings, same as the rest of the app.
    static void applyBackground(Window window) {
        Backgrounds.apply(window, App.instance.getAppearance());
    }

    // A draggable card centered in the window.
    static Container createCard(Container main, double width, double height) {
        double x = (main.getWidth() - width) / 2.0;
        double y = (main.getHeight() - height) / 2.0;
        Container card = new Container(x, y, width, height);
        card.useTheme();
        card.getStyling().setCornerRadius(24f);
        card.setDraggable(true);
        main.addElement(card);
        return card;
    }

    static StackLayout gradientBadge(double size, IconAsset icon, double iconSize) {
        StackLayout badge = new StackLayout(size, size);
        badge.setAlignment(Layout.Alignment.CENTER);
        badge.getStyling().setBackgroundColor(new LinearGradient(accentStart(), accentEnd()));
        badge.getStyling().setCornerRadius((float) (size / 2));
        IconOverlay badgeIcon = new IconOverlay(icon, iconSize, iconSize);
        badgeIcon.setTint(Color.WHITE);
        badge.addElement(badgeIcon);
        return badge;
    }

    static ButtonOverlay gradientButton(String text, float fontSize, double width, double height) {
        ButtonOverlay button = new ButtonOverlay(text, fontSize, Color.WHITE, width, height);
        button.setTextColor(Color.WHITE);
        button.getStyling().setBackgroundColor(new LinearGradient(accentStart(), accentEnd()));
        button.getStyling().setHoverColor(new LinearGradient(lighter(accentStart()), lighter(accentEnd())));
        button.getStyling().setBorderThickness(0f);
        button.getStyling().setCornerRadius((float) (height / 2));
        return button;
    }

    // Returns the footer's y so screens can lay out the rest above it.
    static double addFooter(Container card, double cardWidth, double cardHeight) {
        TextOverlay footer = new TextOverlay("Powered by RenGL", 12f, footerText());
        double y = cardHeight - 20 - footer.getHeight();
        footer.setPosition((cardWidth - footer.getWidth()) / 2.0, y);
        card.addElement(footer);
        return y;
    }

    /**
     * The badge, gradient title, subtitle and divider at the top of a setup card, centered.
     *
     * @return the y below the header
     */
    static double addHeader(Container card, double cardWidth, double padding, IconAsset icon, String title, String subtitle) {
        double spacing = 14;
        double cursorY = padding;

        StackLayout badge = gradientBadge(56, icon, 24);
        badge.setPosition((cardWidth - 56) / 2.0, cursorY);
        card.addElement(badge);
        cursorY += 56 + spacing;

        TextOverlay titleText = new TextOverlay(title);
        titleText.setFontSize(26f);
        titleText.setTextColor(new ScrollingGradient(accentStart(), accentEnd(), 40f));
        titleText.setPosition((cardWidth - titleText.getWidth()) / 2.0, cursorY);
        card.addElement(titleText);
        cursorY += titleText.getHeight() + spacing;

        TextOverlay subtitleText = new TextOverlay(subtitle, 14f, mutedText());
        subtitleText.setPosition((cardWidth - subtitleText.getWidth()) / 2.0, cursorY);
        card.addElement(subtitleText);
        cursorY += subtitleText.getHeight() + spacing;

        SeparatorOverlay divider = new SeparatorOverlay(cardWidth - 2 * padding);
        divider.setLineColor(divider());
        divider.setPosition(padding, cursorY);
        card.addElement(divider);
        return cursorY + divider.getHeight() + spacing * 1.5;
    }
}
