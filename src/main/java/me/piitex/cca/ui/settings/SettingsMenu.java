package me.piitex.cca.ui.settings;

import me.piitex.cca.App;
import me.piitex.cca.background.Backgrounds;
import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.theme.AppTheme;
import me.piitex.cca.theme.AppTheme.Slot;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.theme.ThemeFiles;
import me.piitex.cca.theme.Themes;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.ContentMenu;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.ColorPickerOverlay;
import me.piitex.engine.ui.overlays.SpinnerOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static me.piitex.cca.ui.settings.SettingsUi.CARD_PADDING;
import static me.piitex.cca.ui.settings.SettingsUi.CONTROL_HEIGHT;
import static me.piitex.cca.ui.settings.SettingsUi.FOOTER_HEIGHT;
import static me.piitex.cca.ui.settings.SettingsUi.PANEL_PADDING;
import static me.piitex.cca.ui.settings.SettingsUi.SPINNER_WIDTH;
import static me.piitex.cca.ui.settings.SettingsUi.TAB_HEIGHT;

/**
 * How the app looks. Works like ModelsMenu: tabs over one copy of the settings ({@link #draft}) and nothing
 * changes until Save & Apply.
 * <p>
 * The Theme tab picks a theme and edits custom ones color by color on working copies ({@link #customs}).
 * The preview is restyled in place as colors change and only Save writes the files and repaints the app.
 */
public class SettingsMenu implements ContentMenu {
    private final MainMenu owner;

    private enum Tab implements SettingsTabs.Tab {
        THEME("Theme", Feather.DROPLET, "theme."),
        BACKGROUND("Background", Feather.IMAGE, "background."),
        CHAT("Chat", Feather.MESSAGE_SQUARE, "chat."),
        CHARACTERS("Characters", Feather.USERS, "characters."),
        GENERAL("General", Feather.SETTINGS, "general.");

        private final String label;
        private final IconAsset icon;
        private final String[] settingPrefixes;

        Tab(String label, IconAsset icon, String... settingPrefixes) {
            this.label = label;
            this.icon = icon;
            this.settingPrefixes = settingPrefixes;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public IconAsset icon() {
            return icon;
        }

        @Override
        public String[] settingPrefixes() {
            return settingPrefixes;
        }
    }

    // The parts of a theme row that are restyled while its theme is edited.
    private record ThemeRow(TextOverlay name, Container[] swatches) {
    }

    private static final Slot[] SWATCH_SLOTS = {Slot.WINDOW, Slot.PANEL, Slot.ACCENT, Slot.ACCENT_END, Slot.TEXT};
    private static final double COLOR_WIDTH = 150;
    private static final double NAME_WIDTH = 240;

    private final AppearanceSettings draft = App.instance.getAppearance().copy();
    // Copies of the custom themes, including any made or deleted since the last save.
    private final List<AppTheme> customs = new ArrayList<>();
    private Tab activeTab = Tab.THEME;
    private boolean closed;
    private String savedMessage;
    // What the last import or export said, shown under the theme buttons until something else happens.
    private String themeNote;
    private boolean themeNoteIsError;
    // The Background tab edits this when the selected theme has its own background, null when it edits the draft.
    private AppearanceSettings backgroundView;

    private final ScrollMemory scrollMemory = new ScrollMemory();
    private final BackgroundTab background;
    private final ChatTab chat;
    private final CharactersTab characters;
    private final GeneralTab general;
    private SettingsFooter footer;
    private ThemePreview preview;
    private final Map<String, ThemeRow> themeRows = new HashMap<>();

    public SettingsMenu(MainMenu owner) {
        this.owner = owner;
        this.background = new BackgroundTab(() -> selected().get(Slot.WINDOW), this::linkRows, scrollMemory, this::backgroundChanged, this::redraw);
        this.chat = new ChatTab(draft, scrollMemory, this::changed, this::redraw);
        this.characters = new CharactersTab(draft, scrollMemory, this::changed);
        this.general = new GeneralTab(draft, scrollMemory, this::changed, this::redraw, this::resetAll);
        reloadCustoms();
    }

    @Override
    public String getTitle() {
        return "Settings";
    }

    @Override
    public void close() {
        closed = true;
    }

    private void redraw() {
        if (!closed) owner.renderContent();
    }

    private void reloadCustoms() {
        customs.clear();
        for (AppTheme saved : Themes.custom()) {
            customs.add(saved.copy());
        }
    }

    @Override
    public Element render(double width, double height) {
        scrollMemory.capture(); // The old form is still showing until this replaces it.
        footer = null;
        preview = null;
        themeRows.clear();
        backgroundView = null;

        Container panel = Components.panel(width, height);
        double innerWidth = Math.max(0, width - 2 * PANEL_PADDING);

        Element tabs = SettingsTabs.bar(Tab.values(), activeTab, innerWidth, this::goToTab);
        tabs.setPosition(PANEL_PADDING, PANEL_PADDING);
        panel.addElement(tabs);

        double bodyY = PANEL_PADDING + TAB_HEIGHT + 20;
        double footerY = height - PANEL_PADDING - FOOTER_HEIGHT;
        double bodyHeight = Math.max(0, footerY - 16 - bodyY);

        Element body = switch (activeTab) {
            case THEME -> buildThemeBody(innerWidth, bodyHeight);
            case BACKGROUND -> background.build(backgroundTarget(), innerWidth, bodyHeight);
            case CHAT -> chat.build(innerWidth, bodyHeight, width, height);
            case CHARACTERS -> characters.build(innerWidth, bodyHeight);
            case GENERAL -> general.build(innerWidth, bodyHeight);
        };
        body.setPosition(PANEL_PADDING, bodyY);
        panel.addElement(body);

        footer = new SettingsFooter(innerWidth, this::save, this::discard, this::resetTab);
        footer.element().setPosition(PANEL_PADDING, footerY);
        panel.addElement(footer.element());

        refreshFooter();
        return panel;
    }

    private void goToTab(Tab tab) {
        if (tab == activeTab) return;
        activeTab = tab;
        themeNote = null;
        scrollMemory.reset();
        redraw();
    }

    // Footer

    private void refreshFooter() {
        if (footer != null) footer.update(isDirty(), savedMessage);
    }

    private void changed() {
        savedMessage = null;
        themeNote = null;
        refreshFooter();
    }

    // The draft, or a stand-in for the selected theme's own background so the tab edits that instead.
    private AppearanceSettings backgroundTarget() {
        AppTheme theme = selected();
        if (theme.background() == null) return draft;
        backgroundView = draft.copy();
        backgroundView.loadBackgroundValues(theme.background());
        return backgroundView;
    }

    // Edits to a theme's own background go back into the theme.
    private void backgroundChanged() {
        if (backgroundView != null) selected().setBackground(backgroundView.backgroundValues());
        changed();
    }

    private boolean isDirty() {
        return draft.differsFrom(App.instance.getAppearance()) || customsDiffer();
    }

    private boolean customsDiffer() {
        List<AppTheme> saved = Themes.custom();
        if (saved.size() != customs.size()) return true;
        for (AppTheme working : customs) {
            AppTheme original = saved.stream().filter(t -> t.id().equals(working.id())).findFirst().orElse(null);
            if (original == null || !original.sameLookAs(working)) return true;
        }
        return false;
    }

    private void save() {
        // Save the themes that changed, then delete the ones that were removed. A copy of a theme that's
        // being deleted still needs to read its background picture, so the delete comes last.
        for (AppTheme working : customs) {
            AppTheme original = Themes.find(working.id());
            if (original == null || !original.sameLookAs(working)) {
                Backgrounds.storeThemeImage(working);
                Themes.saveCustom(App.environment, working);
            }
        }
        for (AppTheme saved : Themes.custom()) {
            if (customs.stream().noneMatch(t -> t.id().equals(saved.id()))) {
                Themes.deleteCustom(App.environment, saved.id());
            }
        }
        Backgrounds.storeImage(draft);
        App.instance.getAppearance().apply(draft);
        Themes.apply(draft.themeId.get());
        App.instance.applyWindowSettings();
        Backgrounds.apply(App.window, App.instance.getAppearance());
        App.logger.info("Saved appearance settings with theme '{}'", draft.themeId.get());
        savedMessage = "Saved.";
        // Repaints the whole app in the new colors, this screen included.
        owner.refreshTheme();
    }

    private void discard() {
        draft.loadFrom(App.instance.getAppearance());
        reloadCustoms();
        savedMessage = null;
        redraw();
    }

    private void resetTab() {
        AppTheme theme = selected();
        if (activeTab == Tab.BACKGROUND && theme.background() != null) {
            // The background on screen is the theme's, so that's the one to reset.
            AppearanceSettings view = backgroundTarget();
            view.resetGroup(activeTab.settingPrefixes());
            theme.setBackground(view.backgroundValues());
        } else {
            draft.resetGroup(activeTab.settingPrefixes());
        }
        savedMessage = null;
        redraw();
    }

    // Every tab back to its defaults. Custom themes stay, and nothing is saved until Save & Apply.
    private void resetAll() {
        draft.resetAll();
        savedMessage = null;
        redraw();
    }

    // Theme tab

    private List<AppTheme> allThemes() {
        List<AppTheme> all = new ArrayList<>(Themes.presets());
        all.addAll(customs);
        return all;
    }

    private AppTheme themeById(String id) {
        return allThemes().stream().filter(t -> t.id().equals(id)).findAny().orElse(null);
    }

    // The theme the draft has picked, or the default if it doesn't exist anymore.
    private AppTheme selected() {
        AppTheme theme = themeById(draft.themeId.get());
        if (theme == null) {
            theme = themeById(Themes.DEFAULT_ID);
            draft.themeId.set(theme.id());
        }
        return theme;
    }

    // The preview stays above the scrolling form so it's in view while a color is picked.
    private Element buildThemeBody(double width, double height) {
        AppTheme current = selected();
        double gap = 16;
        Container holder = Components.transparent(width, height);
        preview = new ThemePreview(width);
        preview.show(current);
        preview.element().setPosition(0, 0);
        holder.addElement(preview.element());

        SettingsForm f = new SettingsForm(width, Math.max(0, height - ThemePreview.HEIGHT - gap), scrollMemory);
        buildThemeTab(f, current);
        Element form = f.finish();
        form.setPosition(0, ThemePreview.HEIGHT + gap);
        holder.addElement(form);
        return holder;
    }

    private void buildThemeTab(SettingsForm f, AppTheme current) {
        f.heading("Themes");
        for (AppTheme theme : allThemes()) {
            f.place(buildThemeRow(theme, theme == current, f.width), 8);
        }
        Container actions = Components.transparent(f.width, 34);
        ButtonOverlay create = Components.secondaryButton("+ New theme", 13f, 130, 34);
        create.onAction(() -> addTheme(current, "New theme"));
        actions.addElement(create);
        ButtonOverlay importer = Components.secondaryButton("Import", 13f, 100, 34);
        importer.setPosition(140, 0);
        importer.onAction(this::importTheme);
        actions.addElement(importer);
        ButtonOverlay exporter = Components.secondaryButton("Export", 13f, 100, 34);
        exporter.setPosition(250, 0);
        exporter.onAction(() -> exportTheme(current));
        actions.addElement(exporter);
        f.place(actions, themeNote == null ? 24 : 10);
        if (themeNote != null) {
            f.place(new TextFlowOverlay(themeNote, f.width, 13f, themeNoteIsError ? Components.WARN : Components.VALID), 24);
        }

        if (current.isBuiltIn()) {
            f.section("Customize");
            ButtonOverlay copy = Components.accentButton("Customize a copy", 14f, 170, CONTROL_HEIGHT);
            copy.onAction(() -> addTheme(current, current.name() + " copy"));
            f.row(current.name() + " is built in", "Built-in themes can't be changed. Make a copy to change its colors, name and shape.", copy);
            f.endCard();
            return;
        }

        f.section("Theme");
        TextFieldOverlay name = new TextFieldOverlay(NAME_WIDTH, CONTROL_HEIGHT);
        name.setPlaceholder("Theme name");
        name.setText(current.name());
        name.onTextChanged(text -> {
            current.setName(text);
            ThemeRow row = themeRows.get(current.id());
            if (row != null) row.name().setText(current.name());
            changed();
        });
        f.row("Name", "How this theme appears in the list.", name);

        SpinnerOverlay radius = new SpinnerOverlay(SPINNER_WIDTH, CONTROL_HEIGHT, 0, AppTheme.MAX_CORNER_RADIUS, current.cornerRadiusValue());
        radius.setStep(1);
        radius.setValue(current.cornerRadiusValue());
        radius.onValueChange(v -> {
            current.setCornerRadius(v.floatValue());
            edited(current);
        });
        f.row("Corner radius", "How rounded panels, fields and buttons that follow the theme are.", radius);
        f.row("Include a background", "Bring the background from the Background tab along with this theme.", linkToggle(current));

        f.section("Accent");
        colorRow(f, current, Slot.ACCENT, "Active tabs, primary buttons, toggles and highlights.");
        colorRow(f, current, Slot.ACCENT_END, "The second color of the gradient on badges and titles.");

        f.section("Surfaces");
        colorRow(f, current, Slot.WINDOW, "Behind everything; the animated background is drawn over it. Always opaque.");
        colorRow(f, current, Slot.PANEL, "The sidebar and the settings panels. Lower its opacity to let the background show through.");
        colorRow(f, current, Slot.FIELD, "Text boxes, dropdowns and message bubbles.");

        f.section("Text");
        colorRow(f, current, Slot.TEXT, "Titles and body text.");
        colorRow(f, current, Slot.MUTED, "Hints, labels and inactive items.");

        f.section("Controls");
        colorRow(f, current, Slot.BUTTON, "Standard buttons.");
        colorRow(f, current, Slot.BORDER, "Outlines around panels, and dividers.");

        f.section("Hover");
        colorRow(f, current, Slot.BUTTON_HOVER, "Standard buttons, and sidebar and tab items, when the mouse is over them.");
        colorRow(f, current, Slot.FIELD_HOVER, "Text boxes and dropdowns when the mouse is over them.");
        colorRow(f, current, Slot.ACCENT_HOVER, "Primary buttons, the send button and the slider handle.");
        colorRow(f, current, Slot.ROW_HOVER, "Character cards and list rows that aren't selected.");

        f.section("Manage");
        ButtonOverlay duplicate = Components.secondaryButton("Duplicate", 13f, 120, CONTROL_HEIGHT);
        duplicate.onAction(() -> addTheme(current, current.name() + " copy"));
        f.row("Duplicate this theme", "Start a new theme from this one.", duplicate);

        ButtonOverlay delete = Components.dangerButton("Delete", 13f, 120, CONTROL_HEIGHT);
        delete.onAction(() -> Components.confirmDelete("Delete " + current.name() + "?",
                "The theme is removed when you save. This can't be undone once saved.", () -> removeTheme(current)));
        f.row("Delete this theme", "Removes it from the list.", delete);
        f.endCard();
    }

    private void colorRow(SettingsForm f, AppTheme edited, Slot slot, String help) {
        ColorPickerOverlay picker = new ColorPickerOverlay(COLOR_WIDTH, CONTROL_HEIGHT, edited.get(slot));
        // The window can't be see through, there's nothing behind it.
        picker.setAlphaEnabled(slot != Slot.WINDOW);
        picker.onChange(color -> {
            edited.set(slot, color);
            edited(edited);
        });
        f.row(slot.label(), help, picker);
    }

    // Restyles the preview and the theme's row in place. A redraw would close the color picker mid drag.
    private void edited(AppTheme edited) {
        if (preview != null) preview.show(edited);
        ThemeRow row = themeRows.get(edited.id());
        if (row != null) paintSwatches(row.swatches(), edited);
        changed();
    }

    // The rows at the top of the Background tab that tie its background to the selected theme.
    private void linkRows(SettingsForm f) {
        AppTheme theme = selected();
        f.section("Theme");
        if (theme.isBuiltIn()) {
            ButtonOverlay copy = Components.accentButton("Customize a copy", 14f, 170, CONTROL_HEIGHT);
            copy.onAction(() -> addTheme(theme, theme.name() + " copy"));
            f.row("Link to a theme", theme.name() + " is built in, so it can't have a background of its own. Make a copy and the background can be linked to that.", copy);
        } else {
            String help = theme.background() == null
                    ? "Keep this background with \"" + theme.name() + "\". Choosing the theme brings it along, other themes keep the shared one."
                    : "This background belongs to \"" + theme.name() + "\", changes here only affect it. Turn off to go back to the shared background.";
            f.row("Link to this theme", help, linkToggle(theme));
        }
        f.endCard();
    }

    // On starts the theme's background as a copy of the shared one so nothing changes on screen. Off drops it.
    private ToggleSwitchOverlay linkToggle(AppTheme theme) {
        ToggleSwitchOverlay toggle = new ToggleSwitchOverlay(44, 24, theme.background() != null);
        toggle.setOnColor(Palette.accent());
        toggle.onToggle(on -> {
            theme.setBackground(on ? draft.backgroundValues() : null);
            savedMessage = null;
            redraw();
        });
        return toggle;
    }

    private void exportTheme(AppTheme theme) {
        String safeName = theme.name().replaceAll("[^A-Za-z0-9._ -]", "_");
        FileDialog.save()
                .filter("Theme", ThemeFiles.EXTENSION)
                .defaultName(safeName + "." + ThemeFiles.EXTENSION)
                .onSelect(picked -> {
                    Path target = picked.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + ThemeFiles.EXTENSION)
                            ? picked : picked.resolveSibling(picked.getFileName() + "." + ThemeFiles.EXTENSION);
                    try {
                        ThemeFiles.export(theme, target);
                        App.logger.info("Exported theme '{}' to {}", theme.name(), target);
                        showThemeNote("Exported " + theme.name() + " to " + target.getFileName() + ".", false);
                    } catch (IOException e) {
                        App.logger.error("Could not export theme '{}'", theme.name(), e);
                        showThemeNote("Couldn't save the theme file.", true);
                    }
                })
                .show();
    }

    // The imported theme is a working copy like a new one, nothing is written until Save & Apply.
    private void importTheme() {
        FileDialog.open()
                .filter("Theme", ThemeFiles.EXTENSION)
                .onSelect(picked -> {
                    try {
                        AppTheme imported = ThemeFiles.read(picked, name -> Themes.newCustomId(name, taken -> themeById(taken) != null));
                        customs.add(imported);
                        customs.sort(Comparator.comparing(t -> t.name().toLowerCase(Locale.ROOT)));
                        draft.themeId.set(imported.id());
                        savedMessage = null;
                        App.logger.info("Imported theme '{}' from {}", imported.name(), picked.getFileName());
                        showThemeNote("Imported " + imported.name() + ". Save & Apply to keep it.", false);
                    } catch (IOException | RuntimeException e) {
                        App.logger.warn("Could not import theme {}", picked, e);
                        showThemeNote("Couldn't read that file as a theme.", true);
                    }
                })
                .show();
    }

    private void showThemeNote(String note, boolean error) {
        themeNote = note;
        themeNoteIsError = error;
        redraw();
    }

    private void addTheme(AppTheme from, String name) {
        String id = Themes.newCustomId(name, taken -> themeById(taken) != null);
        AppTheme created = from.copy(id, name, false);
        // New themes start with the background linked, as a copy of the shared one so nothing changes on screen.
        if (created.background() == null) created.setBackground(draft.backgroundValues());
        customs.add(created);
        customs.sort(Comparator.comparing(t -> t.name().toLowerCase(Locale.ROOT)));
        draft.themeId.set(id);
        savedMessage = null;
        themeNote = null;
        redraw();
    }

    private void removeTheme(AppTheme removed) {
        customs.remove(removed);
        if (removed.id().equals(draft.themeId.get())) draft.themeId.set(Themes.DEFAULT_ID);
        savedMessage = null;
        redraw();
    }

    private void select(AppTheme picked) {
        if (picked.id().equals(draft.themeId.get())) return;
        draft.themeId.set(picked.id());
        savedMessage = null;
        themeNote = null;
        redraw();
    }

    private Element buildThemeRow(AppTheme theme, boolean selected, double width) {
        Runnable pick = () -> select(theme);
        SettingsUi.OptionRow option = SettingsUi.optionRow(width, theme.name(), theme.isBuiltIn() ? "Built in" : theme.background() == null ? "Custom" : "Custom · with background", selected, pick);
        Container row = option.row();

        // The theme's main colors as dots so themes can be told apart at a glance.
        double dotSize = 20, dotGap = 6;
        Container[] swatches = new Container[SWATCH_SLOTS.length];
        double x = width - CARD_PADDING - SWATCH_SLOTS.length * dotSize - (SWATCH_SLOTS.length - 1) * dotGap;
        for (int i = 0; i < swatches.length; i++) {
            Container dot = new Container(dotSize, dotSize);
            dot.getStyling().setBorderThickness(1f);
            dot.getStyling().setCornerRadius((float) (dotSize / 2));
            dot.setPosition(x + i * (dotSize + dotGap), (row.getHeight() - dotSize) / 2.0);
            dot.setCursorMode(CursorMode.POINTER);
            dot.onMouseClick(event -> pick.run());
            row.addElement(dot);
            swatches[i] = dot;
        }
        paintSwatches(swatches, theme);
        themeRows.put(theme.id(), new ThemeRow(option.title(), swatches));
        return row;
    }

    private static void paintSwatches(Container[] swatches, AppTheme theme) {
        for (int i = 0; i < swatches.length; i++) {
            Components.fill(swatches[i], theme.get(SWATCH_SLOTS[i]));
            // A gray ring keeps a swatch visible when it matches the row's background.
            swatches[i].getStyling().setBorderColor(new Color(0.5f, 0.5f, 0.5f, 0.55f));
        }
    }
}
