package me.piitex.cca.ui.setup;

import me.piitex.cca.App;
import me.piitex.cca.backend.server.CloudEndpoint;
import me.piitex.cca.config.ModelSettings;
import me.piitex.cca.ui.Components;
import me.piitex.engine.Window;
import me.piitex.engine.WindowStyle;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;


// Opened when local setup fails: chat goes to the user's own llama.cpp-compatible endpoint instead (the same one Models >
// Cloud edits). Nothing is saved until Continue, so the connection can be tested first.
public class CloudSetupMenu {
    private static final Color OK_COLOR = new Color(0.30f, 0.78f, 0.45f, 1f);
    private static final Color ERROR_COLOR = new Color(0.90f, 0.32f, 0.32f, 1f);

    private final Window window = App.window;

    private Container main;
    private Container card;
    private Container statusBox;
    private TextFieldOverlay url;
    private TextFieldOverlay apiKey;
    private TextFieldOverlay model;
    private ButtonOverlay test;
    private double contentWidth;
    private boolean testing;

    public CloudSetupMenu() {
        createMenu();
    }

    private void createMenu() {
        window.setSize(800, 720);
        window.setStyle(WindowStyle.BORDERLESS);
        SetupMenu.applyBackground(window);

        main = new Container(window.getWindowOptions().getWidth(), window.getWindowOptions().getHeight());
        window.addContainer(main);

        double padding = 40;
        double cardWidth = 640, cardHeight = 680;
        card = SetupMenu.createCard(main, cardWidth, cardHeight);
        contentWidth = cardWidth - 2 * padding;

        SetupMenu.addFooter(card, cardWidth, cardHeight);
        double y = SetupMenu.addHeader(card, cardWidth, padding, Feather.CLOUD, "Connect to the Cloud", "Chat through your own endpoint instead of a local model.");

        url = field("Endpoint URL", "http://192.168.1.20:8080 or https://llama.example.com/v1", false, padding, y);
        y += 76;
        apiKey = field("API key", "Optional. Leave blank if the endpoint is open.", true, padding, y);
        y += 76;
        model = field("Model name", "Optional. Blank lets the server use whatever it has loaded.", false, padding, y);
        y += 76;

        // Result of the connection test. Rebuilt every time it changes.
        statusBox = Components.transparent(contentWidth, 36);
        statusBox.setPosition(padding, y);
        card.addElement(statusBox);
        showStatus("Test the connection before you continue, or continue and fix it later under Models > Cloud.", SetupMenu.mutedText());
        y += 36 + 8;

        double buttonGap = 14, buttonWidth = (contentWidth - buttonGap) / 2;
        test = Components.secondaryButton("Test connection", 15f, buttonWidth, 48);
        test.setPosition(padding, y);
        test.onAction(this::testConnection);
        card.addElement(test);

        ButtonOverlay next = SetupMenu.gradientButton("Save & Continue", 15f, buttonWidth, 48);
        next.setPosition(padding + buttonWidth + buttonGap, y);
        next.onAction(this::saveAndContinue);
        card.addElement(next);
        y += 48 + 8;

        ButtonOverlay again = Components.textButton("Try local setup again", contentWidth, 32);
        again.getStyling().setBorderThickness(0f);
        again.setPosition(padding, y);
        again.onAction(() -> card.fadeOut(0.3f, () -> {
            window.removeContainer(main);
            new LocalSetupMenu();
        }));
        card.addElement(again);

        card.fadeIn(0.75f);
    }

    // A small label over a text field, y is the top of the label.
    private TextFieldOverlay field(String label, String placeholder, boolean secret, double x, double y) {
        TextOverlay name = new TextOverlay(label, 13f, SetupMenu.mutedText());
        name.setPosition(x, y);
        card.addElement(name);

        TextFieldOverlay field = new TextFieldOverlay(contentWidth, 44);
        field.setPlaceholder(placeholder);
        field.setPassword(secret);
        field.setPosition(x, y + 22);
        card.addElement(field);
        return field;
    }

    private void showStatus(String text, Color color) {
        Components.clear(statusBox);
        TextFlowOverlay flow = new TextFlowOverlay(text, contentWidth, 13f, color);
        flow.setTextAlign(TextFlowOverlay.TextAlign.CENTER);
        statusBox.addElement(flow);
    }

    // Typed without the scheme, "192.168.1.20:8080" would fail as a URL.
    private static String withScheme(String typed) {
        String trimmed = typed.trim();
        return trimmed.isEmpty() || trimmed.contains("://") ? trimmed : "http://" + trimmed;
    }

    // What's typed so far, on a copy so nothing is saved by testing.
    private ModelSettings typedSettings() {
        ModelSettings settings = App.instance.getModelSettings().copy();
        settings.cloudEnabled.set(true);
        settings.cloudUrl.set(withScheme(url.getText()));
        settings.setCloudApiKey(apiKey.getText());
        settings.cloudModel.set(model.getText().trim());
        return settings;
    }

    private void testConnection() {
        if (testing) return;
        if (url.getText().isBlank()) {
            showStatus("Enter the endpoint URL first.", ERROR_COLOR);
            return;
        }
        testing = true;
        showStatus("Testing...", SetupMenu.mutedText());
        ModelSettings settings = typedSettings();
        Thread.ofVirtual().name("cloud-setup-test").start(() -> {
            CloudEndpoint.TestResult result = CloudEndpoint.test(settings);
            App.logger.info("Cloud setup test: {}", result.message());
            Scheduler.runLater(() -> {
                testing = false;
                showStatus(result.message(), result.ok() ? OK_COLOR : ERROR_COLOR);
            });
        });
    }

    private void saveAndContinue() {
        if (url.getText().isBlank()) {
            showStatus("Enter the endpoint URL first.", ERROR_COLOR);
            return;
        }
        App.instance.getModelSettings().apply(typedSettings());
        App.logger.info("Cloud endpoint saved during setup.");
        SetupMenu.finishSetup(card, main);
    }
}
