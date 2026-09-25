package me.piitex.cca.ui;

import me.piitex.cca.App;
import me.piitex.cca.crypto.FileCrypter;
import me.piitex.cca.ui.setup.SetupMenu;
import me.piitex.cca.ui.setup.UnlockMenu;
import me.piitex.engine.Window;

public class MenuManager {
    private final Window window;

    public MenuManager(Window window) {
        this.window = window;

        // The menu manager manages how and which menus are displayed.
        // With an app password nothing else can load until it's been entered, the characters, chats and API keys need the key.
        if (FileCrypter.isLocked(App.environment)) {
            App.logger.info("The app is password protected, asking for it...");
            new UnlockMenu(this::start);
        } else {
            start();
        }
    }

    // On a first run the setup menu needs to be displayed. app.conf is created with defaults as soon as
    // AppConfig loads, so it existing doesn't mean setup was done. setupComplete is only set once setup finishes.
    private void start() {
        App.instance.loadEncryptedSettings();
        if (!App.instance.getAppConfig().isSetupComplete()) {
            App.logger.info("Setup has not been completed, opening setup...");
            new SetupMenu();
        } else {
            new MainMenu();
        }
    }

    public Window getWindow() {
        return window;
    }
}
