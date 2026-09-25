package me.piitex.cca.ui.setup;

import me.piitex.cca.App;
import me.piitex.cca.crypto.FileCrypter;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.Window;
import me.piitex.engine.WindowStyle;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

import java.io.IOException;


// The last step of setup. Optional: locks the chat and API key encryption key with a password that's asked for when
// the app opens. Skipping it is fine, Settings > General can set one later.
public class PasswordSetupMenu {
    private static final Color ERROR_COLOR = new Color(0.90f, 0.32f, 0.32f, 1f);

    private final Window window = App.window;

    private Container main;
    private Container card;
    private TextFieldOverlay password;
    private TextFieldOverlay confirm;
    private TextOverlay message;
    private double cardWidth;

    public PasswordSetupMenu() {
        createMenu();
    }

    private void createMenu() {
        window.setSize(800, 680);
        window.setStyle(WindowStyle.BORDERLESS);
        SetupMenu.applyBackground(window);

        main = new Container(window.getWindowOptions().getWidth(), window.getWindowOptions().getHeight());
        window.addContainer(main);

        double padding = 40;
        cardWidth = 460;
        double cardHeight = 600;
        card = SetupMenu.createCard(main, cardWidth, cardHeight);

        double contentWidth = cardWidth - 2 * padding;
        SetupMenu.addFooter(card, cardWidth, cardHeight);
        double y = SetupMenu.addHeader(card, cardWidth, padding, Feather.LOCK, "Protect Your Data", "Optional. Ask for a password when CCA opens.");

        TextFlowOverlay info = new TextFlowOverlay("Your characters, chats and saved API keys are already encrypted. A password keeps them locked until you enter it. "
                + "There's no way to recover a forgotten password.", contentWidth, 13f, SetupMenu.mutedText());
        info.setTextAlign(TextFlowOverlay.TextAlign.CENTER);
        info.setPosition(padding, y);
        card.addElement(info);
        y += info.getHeight() + 16;

        password = field("New password", padding, y, contentWidth);
        y += 44 + 10;
        confirm = field("Confirm password", padding, y, contentWidth);
        confirm.onSubmit(text -> save());
        y += 44 + 8;

        message = new TextOverlay(" ", 13f, ERROR_COLOR);
        message.setPosition(padding, y);
        card.addElement(message);
        y += 26;

        ButtonOverlay set = SetupMenu.gradientButton("Set Password", 15f, contentWidth, 48);
        set.setPosition(padding, y);
        set.onAction(this::save);
        card.addElement(set);
        y += 48 + 8;

        ButtonOverlay skip = Components.textButton("Skip for now", contentWidth, 32);
        skip.getStyling().setBorderThickness(0f);
        skip.setPosition(padding, y);
        skip.onAction(this::finish);
        card.addElement(skip);

        card.fadeIn(0.75f);
    }

    private TextFieldOverlay field(String placeholder, double x, double y, double width) {
        TextFieldOverlay field = new TextFieldOverlay(width, 44);
        field.setPlaceholder(placeholder);
        field.setPassword(true);
        field.setPosition(x, y);
        card.addElement(field);
        return field;
    }

    private void save() {
        String problem = FileCrypter.passwordProblem(password.getText(), confirm.getText());
        if (problem == null) {
            try {
                FileCrypter.setPassword(App.environment, password.getText().toCharArray());
                App.logger.info("App password set.");
                finish();
                return;
            } catch (IOException e) {
                App.logger.error("Could not set the app password", e);
                problem = "Could not save it: " + e.getMessage();
            }
        }
        message.setText(problem);
        message.setTextColor(ERROR_COLOR);
        message.setPosition((cardWidth - message.getWidth()) / 2.0, message.getY());
    }

    private void finish() {
        card.fadeOut(0.3f, () -> {
            window.removeContainer(main);
            new MainMenu();
        });
    }
}
