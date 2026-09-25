package me.piitex.cca.ui.settings;

import me.piitex.cca.backend.local.BackendInstaller;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.ContentMenu;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.image.IconAsset;

import static me.piitex.cca.ui.settings.SettingsUi.PANEL_PADDING;
import static me.piitex.cca.ui.settings.SettingsUi.TAB_HEIGHT;

/**
 * The Updates screen. The Backend tab installs and updates llama.cpp, the App tab is for updating CCA itself
 * and isn't built yet. Nothing here is saved the way the settings are, each button does its work as it's pressed.
 */
public class UpdatesMenu implements ContentMenu {
    private final MainMenu owner;

    private enum Tab implements SettingsTabs.Tab {
        BACKEND("Backend", Feather.CPU),
        APP("App", Feather.PACKAGE);

        private final String label;
        private final IconAsset icon;

        Tab(String label, IconAsset icon) {
            this.label = label;
            this.icon = icon;
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
            return new String[0];
        }
    }

    private final ScrollMemory scrollMemory = new ScrollMemory();
    private final BackendTab backend = new BackendTab(scrollMemory, this::redraw);
    // An install outlives this screen, so it stops listening when it closes.
    private final Runnable installerEvent = () -> Scheduler.runLater(this::installerChanged);
    private Tab activeTab = Tab.BACKEND;
    private boolean closed;

    public UpdatesMenu(MainMenu owner) {
        this.owner = owner;
        BackendInstaller.get().addListener(installerEvent);
    }

    @Override
    public String getTitle() {
        return "Updates";
    }

    @Override
    public void close() {
        closed = true;
        backend.close();
        BackendInstaller installer = BackendInstaller.get();
        installer.removeListener(installerEvent);
        installer.acknowledge(); // The result was shown, no need to show it again next visit.
    }

    private void redraw() {
        if (!closed) owner.renderContent();
    }

    private void installerChanged() {
        if (!closed) backend.installerChanged();
    }

    @Override
    public Element render(double width, double height) {
        scrollMemory.capture(); // The old form is still showing until this replaces it.

        Container panel = Components.panel(width, height);
        double innerWidth = Math.max(0, width - 2 * PANEL_PADDING);

        Element tabs = SettingsTabs.bar(Tab.values(), activeTab, innerWidth, this::goToTab);
        tabs.setPosition(PANEL_PADDING, PANEL_PADDING);
        panel.addElement(tabs);

        double bodyY = PANEL_PADDING + TAB_HEIGHT + 20;
        double bodyHeight = Math.max(0, height - PANEL_PADDING - bodyY);
        Element body = switch (activeTab) {
            case BACKEND -> backend.build(innerWidth, bodyHeight);
            case APP -> Components.emptyState(innerWidth, bodyHeight, Feather.PACKAGE, "Updating the app isn't built yet",
                    "Updates for CCA itself will be installed from here.");
        };
        body.setPosition(PANEL_PADDING, bodyY);
        panel.addElement(body);
        return panel;
    }

    private void goToTab(Tab tab) {
        if (tab == activeTab) return;
        activeTab = tab;
        scrollMemory.reset();
        redraw();
    }
}
