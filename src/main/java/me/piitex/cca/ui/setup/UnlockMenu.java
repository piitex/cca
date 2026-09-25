package me.piitex.cca.ui.setup;

import me.piitex.cca.App;
import me.piitex.cca.crypto.FileCrypter;
import me.piitex.cca.ui.Components;
import me.piitex.engine.Window;
import me.piitex.engine.WindowStyle;
import me.piitex.engine.io.AppEnvironment;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;


// Asks for the app password before anything else loads, since everything the user wrote is encrypted with the key it protects.
// onUnlocked runs once it's been entered (or the data was reset).
public class UnlockMenu {
    private static final Color ERROR_COLOR = new Color(0.90f, 0.32f, 0.32f, 1f);

    private final Window window = App.window;
    private final Runnable onUnlocked;

    private Container main;
    private Container card;
    private TextFieldOverlay password;
    private TextOverlay message;
    private double cardWidth;
    private boolean busy;

    public UnlockMenu(Runnable onUnlocked) {
        this.onUnlocked = onUnlocked;
        createMenu();
    }

    private void createMenu() {
        window.setSize(800, 600);
        window.setStyle(WindowStyle.BORDERLESS);
        SetupMenu.applyBackground(window);

        main = new Container(window.getWindowOptions().getWidth(), window.getWindowOptions().getHeight());
        window.addContainer(main);

        double padding = 40;
        cardWidth = 420;
        double cardHeight = 460;
        card = SetupMenu.createCard(main, cardWidth, cardHeight);

        double contentWidth = cardWidth - 2 * padding;
        SetupMenu.addFooter(card, cardWidth, cardHeight);
        double y = SetupMenu.addHeader(card, cardWidth, padding, Feather.LOCK, "CCA is locked", "Enter your app password to continue.");

        password = new TextFieldOverlay(contentWidth, 44);
        password.setPlaceholder("App password");
        password.setPassword(true);
        password.setPosition(padding, y);
        password.onSubmit(text -> attempt());
        card.addElement(password);
        y += 44 + 10;

        message = new TextOverlay(" ", 13f, ERROR_COLOR);
        message.setPosition(padding, y);
        card.addElement(message);
        y += 26;

        ButtonOverlay unlock = SetupMenu.gradientButton("Unlock", 15f, contentWidth, 48);
        unlock.setPosition(padding, y);
        unlock.onAction(this::attempt);
        card.addElement(unlock);
        y += 48 + 8;

        ButtonOverlay forgot = Components.textButton("Forgot password?", contentWidth, 32);
        forgot.getStyling().setBorderThickness(0f);
        forgot.setPosition(padding, y);
        forgot.onAction(this::confirmReset);
        card.addElement(forgot);

        card.fadeIn(0.75f);
        Scheduler.runLater(() -> window.requestFocus(password));
    }

    private void say(String text, Color color) {
        message.setText(text.isEmpty() ? " " : text);
        message.setTextColor(color);
        message.setPosition((cardWidth - message.getWidth()) / 2.0, message.getY());
    }

    private void attempt() {
        if (busy) return;
        char[] typed = password.getText().toCharArray();
        if (typed.length == 0) {
            say("Enter your password.", ERROR_COLOR);
            return;
        }
        busy = true;
        say("Checking...", SetupMenu.mutedText());
        // Checking is slow on purpose, so it stays off the render thread.
        Thread.ofVirtual().name("unlock").start(() -> {
            boolean ok = false;
            String problem = null;
            try {
                ok = FileCrypter.unlock(App.environment, typed);
            } catch (IOException e) {
                App.logger.error("Could not unlock the app", e);
                problem = "Could not read the key: " + e.getMessage();
            }
            boolean unlocked = ok;
            String error = problem;
            Scheduler.runLater(() -> {
                if (unlocked) {
                    finish();
                } else {
                    busy = false;
                    password.setText("");
                    say(error != null ? error : "That password isn't right.", ERROR_COLOR);
                    window.requestFocus(password);
                }
            });
        });
    }

    private void finish() {
        card.fadeOut(0.3f, () -> {
            window.removeContainer(main);
            onUnlocked.run();
        });
    }

    private void confirmReset() {
        if (busy) return;
        Components.confirmDelete("Reset app data?",
                "There's no way to recover the password. This deletes every character, user, chat, folder and saved API key so CCA can open again. Your themes and settings are kept. This can't be undone.",
                "Reset", this::resetData);
    }

    // The old key is gone, so nothing encrypted with it can be read anymore and is removed. The next run makes a new key.
    private void resetData() {
        AppEnvironment environment = App.environment;
        try {
            FileCrypter.discardKey(environment);
            deleteAll(environment.getDataPath("characters"));
            deleteAll(environment.getDataPath("users"));
            Files.deleteIfExists(environment.getDataPath("data/layout.conf"));
            App.logger.warn("The app password was reset. Characters, users, chats and saved API keys were removed.");
        } catch (IOException e) {
            App.logger.error("Could not reset the app data", e);
            say("Could not reset: " + e.getMessage(), ERROR_COLOR);
            return;
        }
        finish();
    }

    private static void deleteAll(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
