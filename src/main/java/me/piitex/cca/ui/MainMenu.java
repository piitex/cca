package me.piitex.cca.ui;

import me.piitex.cca.App;
import me.piitex.cca.background.Backgrounds;
import me.piitex.cca.config.AppConfig;
import me.piitex.cca.model.Character;
import me.piitex.cca.model.User;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.chat.ChatMenu;
import me.piitex.cca.ui.creator.CharacterCreatorMenu;
import me.piitex.cca.ui.creator.CreatorMenu;
import me.piitex.cca.ui.creator.UserCreatorMenu;
import me.piitex.cca.ui.settings.ModelsMenu;
import me.piitex.cca.ui.settings.SettingsMenu;
import me.piitex.engine.Window;
import me.piitex.engine.WindowStyle;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.SplitContainer;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.StackLayout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.SeparatorOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.theme.Theme;
import me.piitex.engine.ui.theme.ThemeManager;

import java.util.LinkedHashMap;
import java.util.Map;

// The main window: the sidebar on the left and whatever section is open on the right.
// The sidebar can be dragged smaller to give the chat more room.
public class MainMenu {
    private final Window window = App.window;
    private final AppConfig appConfig = App.instance.getAppConfig();
    private final Theme theme = ThemeManager.getCurrent();

    private static final double INITIAL_SIDEBAR_WIDTH = 240;
    private static final float MIN_PANE_SIZE = 20f;
    private static final float MAX_SIDEBAR_WIDTH = 420f;
    private static final double PADDING = 28;
    private static final double CREATE_BUTTON_WIDTH = 480;
    private static final double CREATE_BUTTON_HEIGHT = 84;

    private enum Section {CHARACTERS, USERS, CREATE, MODELS, UPDATES, SETTINGS}

    private record NavItem(Section section, IconAsset icon, String label) {
    }

    private record NavRow(HorizontalLayout row, IconOverlay icon, TextOverlay label) {
    }

    private static final NavItem[] NAV_ITEMS = {
            new NavItem(Section.CHARACTERS, Feather.USERS, "Characters"),
            new NavItem(Section.USERS, Feather.USER, "Users"),
            new NavItem(Section.CREATE, Feather.USER_PLUS, "Create"),
            new NavItem(Section.MODELS, Feather.CPU, "Models"),
            new NavItem(Section.UPDATES, Feather.REFRESH_CW, "Updates"),
            new NavItem(Section.SETTINGS, Feather.SETTINGS, "Settings"),
    };

    private final Map<Section, NavRow> navRows = new LinkedHashMap<>();
    private final CharacterHub hub = new CharacterHub(this);
    private final UserHub userHub = new UserHub(this);
    private Section activeSection = Section.CHARACTERS;

    // The chat, a creator, models or settings. Null shows the section's own page (the hub, the create picker).
    private ContentMenu openMenu;

    private SplitContainer main;
    private Container sidebar;
    private Container content;

    private int windowWidth;
    private int windowHeight;

    public MainMenu() {
        createMenu();
    }

    private void createMenu() {
        window.setStyle(WindowStyle.STANDARD);

        // Settings > Background, new installs get the constellation.
        Backgrounds.apply(window, App.instance.getAppearance());

        windowWidth = appConfig.getWidth();
        windowHeight = appConfig.getHeight();
        // The lock and setup screens are smaller and open wherever the OS put them, so the bigger window would run off the screen.
        boolean resized = window.getWindowOptions().getWidth() != windowWidth || window.getWindowOptions().getHeight() != windowHeight;
        window.setSize(windowWidth, windowHeight);
        if (resized) window.center();

        main = new SplitContainer(SplitContainer.Orientation.HORIZONTAL, windowWidth, windowHeight);
        main.setDividerPosition(INITIAL_SIDEBAR_WIDTH / windowWidth);
        main.setMinPaneSize(MIN_PANE_SIZE);
        main.setMaxPaneSize(MAX_SIDEBAR_WIDTH); // Only caps the sidebar, chat can grow as much as it wants.
        window.addContainer(main);

        sidebar = new Container(0, 0, 0, 0);
        sidebar.useTheme();
        styleSidebar();
        main.setFirst(sidebar);

        content = new Container(0, 0, 0, 0);
        main.setSecond(content);

        renderSidebar();
        renderContent();

        // The split container resizes the panes on its own but everything in them is positioned by hand.
        main.onDividerMoved(position -> {
            renderSidebar();
            renderContent();
        });
        window.onResize(this::onWindowResize);
        window.onClose(() -> {
            appConfig.setWidth(windowWidth);
            appConfig.setHeight(windowHeight);
        });

        main.fadeIn(0.5f);
    }

    // Slightly see through so the background shows a little, otherwise the sidebar looks dead next to the content.
    private void styleSidebar() {
        Components.fill(sidebar, Palette.panel(0.92f));
    }

    // Repaints everything after a theme change. Nothing is recreated so an open chat or half filled creator keeps its state.
    public void refreshTheme() {
        styleSidebar();
        renderSidebar();
        renderContent();
    }

    private void onWindowResize(int width, int height) {
        windowWidth = width;
        windowHeight = height;

        main.setWidth(width);
        main.setHeight(height);
        main.relayout(); // Works out the new pane sizes before redrawing what's in them.

        renderSidebar();
        renderContent();
    }

    private void renderSidebar() {
        Components.clear(sidebar);
        navRows.clear();

        double width = sidebar.getWidth();
        double inset = 20;
        double cursorY = 24;
        double badgeSize = 36;

        StackLayout badge = Components.gradientBadge(badgeSize, Feather.MESSAGE_CIRCLE, 18);
        badge.setPosition(inset, cursorY);
        sidebar.addElement(badge);

        TextOverlay wordmark = new TextOverlay("CCA", 18f, theme.getText());
        wordmark.setPosition(inset + badgeSize + 10, cursorY + (badgeSize - wordmark.getHeight()) / 2.0);
        sidebar.addElement(wordmark);
        cursorY += badgeSize + 20;

        SeparatorOverlay divider = new SeparatorOverlay(Math.max(0, width - 2 * inset));
        divider.setLineColor(theme.getContainerBorder());
        divider.setPosition(inset, cursorY);
        sidebar.addElement(divider);
        cursorY += divider.getHeight() + 16;

        for (NavItem item : NAV_ITEMS) {
            NavRow navRow = buildNavRow(item, inset, cursorY, Math.max(0, width - 2 * inset));
            sidebar.addElement(navRow.row());
            navRows.put(item.section(), navRow);
            cursorY += navRow.row().getHeight() + 6;
        }

        highlightActiveNav();
    }

    private NavRow buildNavRow(NavItem item, double x, double y, double width) {
        HorizontalLayout row = new HorizontalLayout(x, y, width, 40);
        row.setAlignment(Layout.Alignment.CENTER_LEFT);
        row.setPadding(10f);
        row.setSpacing(10f);
        row.useTheme(); // For the corner radius, the colors are set in highlightActiveNav.
        row.getStyling().setBackgroundColor(Color.TRANSPARENT);
        row.getStyling().setHoverColor(theme.getButtonHoverBackground());
        row.setCursorMode(CursorMode.POINTER);

        IconOverlay icon = new IconOverlay(item.icon(), 18, 18);
        icon.setTint(theme.getPlaceholderText());
        row.addElement(icon);

        TextOverlay label = new TextOverlay(item.label(), 14f, theme.getPlaceholderText());
        row.addElement(label);

        row.onMouseClick(event -> showSection(item.section()));
        return new NavRow(row, icon, label);
    }

    private void highlightActiveNav() {
        for (Map.Entry<Section, NavRow> entry : navRows.entrySet()) {
            boolean active = entry.getKey() == activeSection;
            NavRow navRow = entry.getValue();
            navRow.row().getStyling().setBackgroundColor(active ? Palette.tint() : Color.TRANSPARENT);
            navRow.row().getStyling().setHoverColor(active ? Palette.tintStrong() : theme.getButtonHoverBackground());
            Color tint = active ? Palette.accent() : theme.getPlaceholderText();
            navRow.icon().setTint(tint);
            navRow.label().setTextColor(tint);
        }
    }

    private void showSection(Section section) {
        // Clicking Characters or Users while a chat or an editor is open goes back to the hub.
        boolean hubSection = section == Section.CHARACTERS || section == Section.USERS;
        if (section == activeSection && !(hubSection && openMenu != null)) return;
        dropMenu();
        activeSection = section;
        if (section == Section.MODELS) openMenu = new ModelsMenu(this);
        if (section == Section.SETTINGS) openMenu = new SettingsMenu(this);
        highlightActiveNav();
        renderContent();
    }

    private void open(Section section, ContentMenu menu) {
        dropMenu();
        activeSection = section;
        openMenu = menu;
        highlightActiveNav();
        renderContent();
    }

    private void dropMenu() {
        if (openMenu != null) openMenu.close();
        openMenu = null;
    }

    // Back out of the chat or creator to the section's own page.
    public void closeMenu() {
        dropMenu();
        renderContent();
    }

    // Where the content area is in the window, x, y, width and height. The folder popout covers just this part.
    double[] contentBounds() {
        return new double[]{content.getAbsoluteX(), content.getAbsoluteY(), content.getWidth(), content.getHeight()};
    }

    // Everything in the content area is rebuilt from scratch. Menus call this when they change.
    public void renderContent() {
        Components.clear(content);

        double contentWidth = content.getWidth();
        double headerY = PADDING;
        String title = openMenu != null ? openMenu.getTitle() : switch (activeSection) {
            case CHARACTERS -> "Characters";
            case USERS -> "Users";
            case CREATE -> "Create";
            case MODELS -> "Models";
            case UPDATES -> "Updates";
            case SETTINGS -> "Settings";
        };

        TextOverlay titleText = new TextOverlay(title, 24f, theme.getText());
        titleText.setPosition(PADDING, headerY);
        content.addElement(titleText);
        double headerBottom = headerY + titleText.getHeight();

        double buttonY = headerY - 4;
        if (openMenu instanceof ChatMenu || openMenu instanceof CreatorMenu) {
            ButtonOverlay back = Components.textButton("< Back", 90, 36);
            back.setPosition(contentWidth - PADDING - 90, buttonY);
            back.onAction(this::closeMenu);
            content.addElement(back);
            headerBottom = Math.max(headerBottom, buttonY + 36);
        } else if (activeSection == Section.CHARACTERS) {
            headerBottom = Math.max(headerBottom, hub.addHeaderButtons(content, contentWidth - PADDING, buttonY));
        } else if (activeSection == Section.USERS) {
            headerBottom = Math.max(headerBottom, userHub.addHeaderButtons(content, contentWidth - PADDING, buttonY));
        }

        double dividerY = headerBottom + 20;
        SeparatorOverlay divider = new SeparatorOverlay(Math.max(0, contentWidth - 2 * PADDING));
        divider.setLineColor(theme.getContainerBorder());
        divider.setPosition(PADDING, dividerY);
        content.addElement(divider);

        double bodyY = dividerY + divider.getHeight() + 20;
        double bodyWidth = Math.max(0, contentWidth - 2 * PADDING);
        double bodyHeight = Math.max(0, content.getHeight() - bodyY - PADDING);

        Element body;
        if (openMenu != null) {
            body = openMenu.render(bodyWidth, bodyHeight);
        } else {
            body = switch (activeSection) {
                case CHARACTERS -> hub.build(bodyWidth, bodyHeight);
                case USERS -> userHub.build(bodyWidth, bodyHeight);
                case CREATE -> buildCreateSelection(bodyWidth, bodyHeight);
                default -> Components.emptyState(bodyWidth, bodyHeight, Feather.SLIDERS, title + " isn't built yet", "Check back in a future update.");
            };
        }
        body.setPosition(PADDING, bodyY);
        content.addElement(body);

        // The folder popouts sit over their hub so they're rebuilt with it.
        hub.updatePopout(activeSection == Section.CHARACTERS && openMenu == null);
        userHub.updatePopout(activeSection == Section.USERS && openMenu == null);
    }

    void openChat(Character character) {
        App.logger.info("Opening chat for '{}'", character.getId());
        open(Section.CHARACTERS, new ChatMenu(this, character));
    }

    void openCreateCharacter() {
        open(Section.CHARACTERS, new CharacterCreatorMenu(this));
    }

    void editCharacter(Character character) {
        open(Section.CREATE, new CharacterCreatorMenu(this, character));
    }

    public void onCharacterCreated(Character character) {
        App.logger.info("Created character '{}'", character.getId());
        hub.add(character);
        dropMenu();
        showSection(Section.CHARACTERS);
    }

    // The edited character is the same object the hub already has, so just go back.
    public void onCharacterEdited(Character character) {
        dropMenu();
        showSection(Section.CHARACTERS);
    }

    void openCreateUser() {
        open(Section.USERS, new UserCreatorMenu(this));
    }

    void editUser(User user) {
        open(Section.USERS, new UserCreatorMenu(this, user));
    }

    public void onUserCreated(User user) {
        App.logger.info("Created user '{}'", user.getId());
        userHub.add(user);
        backToUsers();
    }

    // The edited user is the same object the hub already has, so just go back.
    public void onUserEdited(User user) {
        backToUsers();
    }

    // The editor is opened from the Users page or from the Create page, either way this ends up on the Users page.
    private void backToUsers() {
        dropMenu();
        if (activeSection == Section.USERS) renderContent();
        else showSection(Section.USERS);
    }

    // Only ever two options, so they're stacked in the middle instead of laid out like a grid.
    private Element buildCreateSelection(double width, double height) {
        VerticalLayout column = new VerticalLayout(width, height);
        column.setSpacing(16f);
        column.setAlignment(Layout.Alignment.CENTER);
        Components.clearStyle(column);

        double rowWidth = Math.min(CREATE_BUTTON_WIDTH, width);
        column.addElement(buildCreateOption(rowWidth, "Character", "The AI side of a chat: persona, first message, scenario.",
                Feather.USERS, () -> open(Section.CREATE, new CharacterCreatorMenu(this))));
        column.addElement(buildCreateOption(rowWidth, "User", "A reusable persona for the human side of a chat.",
                Feather.USER, () -> open(Section.CREATE, new UserCreatorMenu(this))));
        return column;
    }

    // Positioned by hand so the chevron sits at the right edge instead of right after the text.
    private Container buildCreateOption(double width, String title, String description, IconAsset icon, Runnable onSelect) {
        Container row = new Container(width, CREATE_BUTTON_HEIGHT);
        row.useTheme();
        row.getStyling().setCornerRadius(16f);
        row.getStyling().setHoverColor(Palette.rowHover());
        row.setCursorMode(CursorMode.POINTER);
        row.onMouseClick(event -> onSelect.run());

        double padding = 18, badgeSize = 46, chevronSize = 18, spacing = 16;

        StackLayout badge = Components.gradientBadge(badgeSize, icon, 20);
        badge.setPosition(padding, (CREATE_BUTTON_HEIGHT - badgeSize) / 2.0);
        row.addElement(badge);

        double textX = padding + badgeSize + spacing;
        double textWidth = Math.max(0, width - textX - spacing - chevronSize - padding);

        TextOverlay titleText = new TextOverlay(title, 16f, theme.getText());
        titleText.setPosition(textX, CREATE_BUTTON_HEIGHT / 2.0 - titleText.getHeight() - 2);
        row.addElement(titleText);

        TextFlowOverlay descriptionText = new TextFlowOverlay(description, textWidth, 12f, theme.getPlaceholderText());
        descriptionText.setPosition(textX, CREATE_BUTTON_HEIGHT / 2.0 + 2);
        row.addElement(descriptionText);

        IconOverlay chevron = new IconOverlay(Feather.CHEVRON_RIGHT, chevronSize, chevronSize);
        chevron.setTint(theme.getPlaceholderText());
        chevron.setPosition(width - padding - chevronSize, (CREATE_BUTTON_HEIGHT - chevronSize) / 2.0);
        row.addElement(chevron);

        return row;
    }
}
