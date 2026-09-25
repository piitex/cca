package me.piitex.cca.ui.setup;

import me.piitex.cca.App;
import me.piitex.cca.backend.local.LocalSetupService;
import me.piitex.cca.backend.local.LocalSetupStage;
import me.piitex.cca.ui.Components;
import me.piitex.engine.Window;
import me.piitex.engine.WindowStyle;
import me.piitex.engine.io.download.Download;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.download.DownloadBarContainer;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.StackLayout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.SeparatorOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

import java.util.Objects;


// Runs local setup and shows a checklist of the steps, with the progress of whatever step is running below it.
// On failure the only way forward is cloud setup. There's no retry here.
public class LocalSetupMenu {
    private final Window window = App.window;

    private static final Color DONE_COLOR = new Color(0.30f, 0.78f, 0.45f, 1f);
    private static final Color FAILED_COLOR = new Color(0.90f, 0.32f, 0.32f, 1f);

    private static final LocalSetupStage[] WORKING_STAGES = {
            LocalSetupStage.DOWNLOADING_BACKEND, LocalSetupStage.DOWNLOADING_MODEL,
            LocalSetupStage.STARTING_SERVER, LocalSetupStage.VERIFYING_SERVER
    };

    private static final String[] STEP_LABELS = {
            "Downloading llama.cpp backend", "Downloading test model", "Starting local server", "Verifying server"
    };

    private enum DotState {PENDING, ACTIVE, DONE, FAILED}

    private final LocalSetupService service = new LocalSetupService(App.environment);

    private Container main;
    private Container card;
    private Container detailArea;
    private double detailWidth;
    private double detailHeight;

    private final StackLayout[] dots = new StackLayout[WORKING_STAGES.length];

    private LocalSetupStage lastRenderedStage;
    private LocalSetupStage lastActiveStage = LocalSetupStage.DOWNLOADING_BACKEND;
    private Object lastDetailKey;

    public LocalSetupMenu() {
        createMenu();
        service.start();
    }

    private void createMenu() {
        window.setSize(800, 680);
        window.setStyle(WindowStyle.BORDERLESS);
        SetupMenu.applyBackground(window);

        main = new Container(window.getWindowOptions().getWidth(), window.getWindowOptions().getHeight());
        window.addContainer(main);

        double padding = 40;
        double cardWidth = 640, cardHeight = 600;
        card = SetupMenu.createCard(main, cardWidth, cardHeight);

        double contentWidth = cardWidth - 2 * padding;
        double spacing = 14;

        double footerY = SetupMenu.addFooter(card, cardWidth, cardHeight);
        double cursorY = SetupMenu.addHeader(card, cardWidth, padding, Feather.CPU, "Setting Up Device", "This only happens once. Sit tight.");

        // The checklist. These rows never move, they only change color.
        double rowHeight = 26, rowSpacing = 10;
        VerticalLayout steps = new VerticalLayout(contentWidth, 4 * rowHeight + 3 * rowSpacing);
        steps.setSpacing((float) rowSpacing);
        steps.setPosition(padding, cursorY);
        Components.clearStyle(steps);
        card.addElement(steps);

        for (int i = 0; i < STEP_LABELS.length; i++) {
            steps.addElement(buildStepRow(i, contentWidth, rowHeight));
        }
        cursorY += steps.getHeight() + spacing * 1.5;

        SeparatorOverlay divider = new SeparatorOverlay(contentWidth);
        divider.setLineColor(SetupMenu.divider());
        divider.setPosition(padding, cursorY);
        card.addElement(divider);
        cursorY += divider.getHeight() + spacing;

        // Swapped out whenever the stage changes, see rebuildDetailArea.
        detailWidth = contentWidth;
        detailHeight = Math.max(0, (footerY - spacing) - cursorY);
        detailArea = new Container(padding, cursorY, detailWidth, detailHeight);
        Components.clearStyle(detailArea);
        card.addElement(detailArea);

        // The service runs on its own thread, so check on it every frame instead of reacting to its listener.
        card.onUpdate(event -> pollService());

        card.fadeIn(1f);
    }

    private HorizontalLayout buildStepRow(int index, double width, double height) {
        HorizontalLayout row = new HorizontalLayout(width, height);
        row.setAlignment(Layout.Alignment.CENTER_LEFT);
        row.setSpacing(10f);
        Components.clearStyle(row);

        StackLayout dot = new StackLayout(20, 20);
        dot.setAlignment(Layout.Alignment.CENTER);
        dot.getStyling().setCornerRadius(10f);
        dot.getStyling().setBackgroundColor(SetupMenu.faint(0.14f));
        row.addElement(dot);
        dots[index] = dot;

        row.addElement(new TextOverlay(STEP_LABELS[index], 15f, SetupMenu.mutedText()));
        return row;
    }

    private void setDotState(StackLayout dot, DotState state) {
        Components.clear(dot);
        switch (state) {
            case PENDING -> dot.getStyling().setBackgroundColor(SetupMenu.faint(0.14f));
            case ACTIVE -> dot.getStyling().setBackgroundColor(new LinearGradient(SetupMenu.accentStart(), SetupMenu.accentEnd()));
            case DONE -> {
                dot.getStyling().setBackgroundColor(DONE_COLOR);
                IconOverlay check = new IconOverlay(Feather.CHECK, 12, 12);
                check.setTint(Color.WHITE);
                dot.addElement(check);
            }
            case FAILED -> {
                dot.getStyling().setBackgroundColor(FAILED_COLOR);
                IconOverlay x = new IconOverlay(Feather.X, 12, 12);
                x.setTint(Color.WHITE);
                dot.addElement(x);
            }
        }
    }

    private void updateStepDots(LocalSetupStage stage) {
        boolean finished = stage == LocalSetupStage.FAILED || stage == LocalSetupStage.SUCCESS;
        int activeIndex = indexOf(finished ? lastActiveStage : stage);

        for (int i = 0; i < dots.length; i++) {
            DotState state;
            if (stage == LocalSetupStage.SUCCESS) {
                state = DotState.DONE;
            } else if (stage == LocalSetupStage.FAILED) {
                state = i < activeIndex ? DotState.DONE : i == activeIndex ? DotState.FAILED : DotState.PENDING;
            } else {
                state = i < activeIndex ? DotState.DONE : i == activeIndex ? DotState.ACTIVE : DotState.PENDING;
            }
            setDotState(dots[i], state);
        }
    }

    private static int indexOf(LocalSetupStage target) {
        for (int i = 0; i < WORKING_STAGES.length; i++) {
            if (WORKING_STAGES[i] == target) return i;
        }
        return -1;
    }

    private void pollService() {
        LocalSetupStage stage = service.getStage();

        if (stage != lastRenderedStage) {
            if (indexOf(stage) >= 0) lastActiveStage = stage;
            updateStepDots(stage);
            lastRenderedStage = stage;
        }

        // A download object shows up a moment after its stage starts, so that also counts as a change.
        Object detailKey = switch (stage) {
            case DOWNLOADING_BACKEND -> service.getBackendDownload() != null ? service.getBackendDownload() : stage;
            case DOWNLOADING_MODEL -> service.getModelDownload() != null ? service.getModelDownload() : stage;
            default -> stage;
        };
        if (!Objects.equals(detailKey, lastDetailKey)) {
            lastDetailKey = detailKey;
            rebuildDetailArea(stage);
        }
    }

    private void rebuildDetailArea(LocalSetupStage stage) {
        Components.clear(detailArea);

        Element content = switch (stage) {
            case DOWNLOADING_BACKEND -> service.getBackendDownload() != null
                    ? buildDownloadBar(service.getBackendDownload())
                    : buildStatus("Checking for the latest llama.cpp release...");
            case DOWNLOADING_MODEL -> service.getModelDownload() != null
                    ? buildDownloadBar(service.getModelDownload())
                    : buildStatus("Preparing test model download...");
            case STARTING_SERVER -> buildStatus("Starting the local server...");
            case VERIFYING_SERVER -> buildStatus("Verifying the server responds...");
            case SUCCESS -> buildSuccessPanel();
            case FAILED -> buildFailurePanel();
            default -> null;
        };
        if (content != null) detailArea.addElement(content);
    }

    private DownloadBarContainer buildDownloadBar(Download download) {
        return new DownloadBarContainer(download, detailWidth);
    }

    private Container buildStatus(String message) {
        VerticalLayout box = new VerticalLayout(detailWidth, 40);
        box.setSpacing(10f);
        Components.clearStyle(box);

        box.addElement(new TextOverlay(message, 13f, SetupMenu.mutedText()));

        ProgressBarOverlay bar = new ProgressBarOverlay(detailWidth, 6);
        bar.setIndeterminate(true);
        bar.setFillColor(SetupMenu.accentStart());
        box.addElement(bar);
        return box;
    }

    private Container buildFailurePanel() {
        VerticalLayout box = resultBox();

        String reason = service.getErrorMessage() != null ? service.getErrorMessage() : "Local setup failed.";
        TextFlowOverlay message = new TextFlowOverlay(reason, detailWidth, 13f, FAILED_COLOR);
        message.setTextAlign(TextFlowOverlay.TextAlign.CENTER);
        box.addElement(message);

        ButtonOverlay continueCloud = SetupMenu.gradientButton("Continue with Cloud Setup", 15f, detailWidth, 48);
        continueCloud.onAction(() -> card.fadeOut(0.3f, () -> {
            window.removeContainer(main);
            new CloudSetupMenu();
        }));
        box.addElement(continueCloud);
        return box;
    }

    private Container buildSuccessPanel() {
        VerticalLayout box = resultBox();

        box.addElement(new TextOverlay("Local model verified. You're ready to chat.", 14f, SetupMenu.mutedText()));

        ButtonOverlay continueButton = SetupMenu.gradientButton("Continue", 15f, detailWidth, 48);
        continueButton.onAction(() -> SetupMenu.finishSetup(card, main));
        box.addElement(continueButton);
        return box;
    }

    private VerticalLayout resultBox() {
        VerticalLayout box = new VerticalLayout(detailWidth, detailHeight);
        box.setSpacing(16f);
        box.setAlignment(Layout.Alignment.TOP_CENTER);
        Components.clearStyle(box);
        return box;
    }
}
