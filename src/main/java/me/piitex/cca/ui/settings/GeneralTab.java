package me.piitex.cca.ui.settings;

import me.piitex.cca.App;
import me.piitex.cca.config.AppConfig;
import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.crypto.FileCrypter;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.overlays.ButtonOverlay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static me.piitex.cca.ui.settings.SettingsUi.CONTROL_HEIGHT;

// The General tab. Frame rate is a draft setting like everything else. The buttons under Window, Data and Security
// act right away and the reset only changes the draft, so it can still be discarded.
final class GeneralTab {
    private static final double BUTTON_WIDTH = 150;

    private final AppearanceSettings draft;
    private final ScrollMemory scroll;
    private final Runnable changed;
    private final Runnable redraw;
    private final Runnable resetAll;

    GeneralTab(AppearanceSettings draft, ScrollMemory scroll, Runnable changed, Runnable redraw, Runnable resetAll) {
        this.draft = draft;
        this.scroll = scroll;
        this.changed = changed;
        this.redraw = redraw;
        this.resetAll = resetAll;
    }

    Element build(double width, double height) {
        SettingsForm f = new SettingsForm(width, height, scroll);
        window(f);
        data(f);
        security(f);
        reset(f);
        return f.finish();
    }

    private void window(SettingsForm f) {
        f.section("Window");
        f.row("Match display refresh", "Syncs to your screen's refresh rate. Turn off to set your own limit.", SettingsUi.toggle(draft.vsync, this::restructured));
        if (!draft.vsync.get()) {
            f.row("Frame rate limit", "The most frames drawn per second. Lower saves power, the animated background is the main cost.", SettingsUi.intSpinner(draft.maxFps, 15, 240, 5, changed));
        }
        ButtonOverlay size = Components.secondaryButton("Reset size", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
        size.onAction(GeneralTab::resetWindowSize);
        f.row("Window size", "Back to " + AppConfig.DEFAULT_WIDTH + " x " + AppConfig.DEFAULT_HEIGHT + " and centered. Happens right away.", size);
        f.endCard();
    }

    private void data(SettingsForm f) {
        Path folder = App.environment.getDataDirectory();
        f.section("Data");
        ButtonOverlay open = Components.secondaryButton("Open folder", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
        open.onAction(() -> openFolder(folder));
        f.row("Data folder", "Characters, chats, models, themes and settings are kept in " + folder + ".", open);
        f.endCard();
    }

    // Locks the chat and API key encryption key with a password that's asked for when the app opens.
    private void security(SettingsForm f) {
        f.section("Security");
        if (!FileCrypter.isPasswordProtected(App.environment)) {
            ButtonOverlay set = Components.secondaryButton("Set password", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
            set.onAction(this::setPassword);
            f.row("App password", "Ask for a password every time CCA opens. Your characters, chats and saved API keys can't be read without it, and a forgotten password can't be recovered.", set);
        } else {
            ButtonOverlay change = Components.secondaryButton("Change password", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
            change.onAction(this::changePassword);
            f.row("Change password", "CCA asks for the password when it opens. Your chats aren't re-encrypted, so this is quick.", change);
            ButtonOverlay remove = Components.dangerButton("Remove password", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
            remove.onAction(this::removePassword);
            f.row("Remove password", "Stop asking for a password. Your characters, chats and API keys stay encrypted, but the key sits next to them again.", remove);
        }
        f.endCard();
    }

    private void setPassword() {
        Components.promptPasswords("Set app password", "Pick at least " + FileCrypter.MIN_PASSWORD_LENGTH + " characters. If you forget it your characters, chats and API keys can't be recovered.",
                List.of("New password", "Confirm password"), "Set password", values -> {
                    String problem = FileCrypter.passwordProblem(values.get(0), values.get(1));
                    if (problem != null) return problem;
                    try {
                        FileCrypter.setPassword(App.environment, values.get(0).toCharArray());
                        App.logger.info("App password set.");
                        return null;
                    } catch (IOException e) {
                        App.logger.error("Could not set the app password", e);
                        return "Could not save it: " + e.getMessage();
                    }
                }, redraw);
    }

    private void changePassword() {
        Components.promptPasswords("Change app password", "Enter the current password, then pick a new one.",
                List.of("Current password", "New password", "Confirm new password"), "Change", values -> {
                    String problem = FileCrypter.passwordProblem(values.get(1), values.get(2));
                    if (problem != null) return problem;
                    try {
                        if (!FileCrypter.changePassword(App.environment, values.get(0).toCharArray(), values.get(1).toCharArray())) {
                            return "The current password isn't right.";
                        }
                        App.logger.info("App password changed.");
                        return null;
                    } catch (IOException e) {
                        App.logger.error("Could not change the app password", e);
                        return "Could not save it: " + e.getMessage();
                    }
                }, redraw);
    }

    private void removePassword() {
        Components.promptPasswords("Remove app password", "Enter the current password. CCA will stop asking for it when it opens.",
                List.of("Current password"), "Remove", values -> {
                    try {
                        if (!FileCrypter.removePassword(App.environment, values.get(0).toCharArray())) {
                            return "That password isn't right.";
                        }
                        App.logger.info("App password removed.");
                        return null;
                    } catch (IOException e) {
                        App.logger.error("Could not remove the app password", e);
                        return "Could not save it: " + e.getMessage();
                    }
                }, redraw);
    }

    private void reset(SettingsForm f) {
        f.section("Reset");
        ButtonOverlay reset = Components.dangerButton("Reset all", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
        reset.onAction(() -> Components.confirmDelete("Reset all settings?",
                "Every tab goes back to its defaults. Your themes, characters and chats are kept, and nothing changes until you save.", "Reset", resetAll));
        f.row("Reset all settings", "Puts the theme, background, chat, characters and general options back to how the app first looked.", reset);
        f.endCard();
    }

    private void restructured() {
        changed.run();
        redraw.run();
    }

    private static void resetWindowSize() {
        App.window.setSize(AppConfig.DEFAULT_WIDTH, AppConfig.DEFAULT_HEIGHT);
        App.window.center();
    }

    // AWT's Desktop can't be used next to the engine's window, so ask the OS directly.
    private static void openFolder(Path folder) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String opener = os.contains("mac") ? "open" : os.contains("win") ? "explorer" : "xdg-open";
        try {
            new ProcessBuilder(opener, folder.toString()).start();
        } catch (IOException e) {
            App.logger.error("Could not open {}", folder, e);
        }
    }
}
