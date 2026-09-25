package me.piitex.cca.ui.settings;

import me.piitex.cca.App;
import me.piitex.cca.backend.local.BackendCatalog;
import me.piitex.cca.backend.local.BackendInstaller;
import me.piitex.cca.backend.local.BackendReleases;
import me.piitex.cca.backend.local.BackendReleases.Listing;
import me.piitex.cca.backend.local.BackendReleases.Offer;
import me.piitex.cca.backend.local.BackendVariant;
import me.piitex.cca.backend.local.BackendVariant.Vendor;
import me.piitex.cca.backend.local.DeviceScan;
import me.piitex.cca.backend.local.InstalledBackend;
import me.piitex.cca.backend.model.HardwareProbe;
import me.piitex.cca.backend.model.ModelLibrary;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.engine.io.download.Download;
import me.piitex.engine.io.download.DownloadState;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.download.DownloadBarContainer;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static me.piitex.cca.ui.settings.SettingsUi.CARD_PADDING;
import static me.piitex.cca.ui.settings.SettingsUi.CONTROL_HEIGHT;

/**
 * The Backend tab on the Updates screen: which llama.cpp build is installed, the newer builds on GitHub, and the
 * ones that suit this computer's graphics card. The installing itself is BackendInstaller's, which keeps going
 * when this screen is closed.
 * <p>
 * What's on the computer and what's on GitHub are both slow to find out, so they're read on another thread
 * and the tab shows a note until they're in.
 */
final class BackendTab {
    private static final double BUTTON_WIDTH = 130;
    private static final double CHECK_WIDTH = 160;

    private final BackendInstaller installer = BackendInstaller.get();
    private final ScrollMemory scroll;
    private final Runnable redraw;
    private boolean closed;

    private boolean started;
    private boolean checking;
    private boolean showOthers;
    private String startError;
    private String checkError;
    private BackendInstaller.Stage lastStage = installer.getStage();

    // What the first look at the computer is doing, and how far along it is, 0 to 1.
    private String scanStep = "Reading the installed backend";
    private double scanProgress;

    // Null until read. installed stays null if there's no working backend on the computer.
    private boolean installedRead;
    private InstalledBackend installed;
    private DeviceScan.Result devices;
    private List<HardwareProbe.Device> gpus = List.of();
    private BackendReleases.Result releases = BackendReleases.cachedResult();

    BackendTab(ScrollMemory scroll, Runnable redraw) {
        this.scroll = scroll;
        this.redraw = redraw;
    }

    void close() {
        closed = true;
    }

    // The installer started, moved on or finished.
    void installerChanged() {
        BackendInstaller.Stage now = installer.getStage();
        // A new backend is in, so the version and the graphics cards it found are different.
        if (now == BackendInstaller.Stage.DONE && lastStage != BackendInstaller.Stage.DONE) readComputer();
        lastStage = now;
        redraw.run();
    }

    Element build(double width, double height) {
        if (!started) {
            started = true;
            readComputer();
            if (releases == null) checkReleases(false);
        }

        SettingsForm f = new SettingsForm(width, height, scroll);
        f.note("The backend is the program that runs the AI model, and the build that matches your graphics card makes replies much faster. "
                + "Installing a different one leaves the old one in place until the new one has been checked, and your models and chats aren't touched.");

        if (!installedRead || devices == null) {
            f.section("Checking this computer");
            f.custom(progressBar(f.width - 2 * CARD_PADDING, scanStep, scanProgress));
            f.endCard();
            return f.finish();
        }

        current(f);
        progress(f);
        available(f);
        return f.finish();
    }

    // What's installed

    private void current(SettingsForm f) {
        Listing latest = installed == null ? null : listingFor(installed.variantId());
        boolean update = latest != null && latest.offer() != null && latest.offer().build() > installed.build();

        ButtonOverlay check = Components.secondaryButton(checking ? "Checking..." : "Check for updates", 13f, CHECK_WIDTH, CONTROL_HEIGHT);
        check.setEnabled(!checking && !installer.isBusy());
        check.onAction(() -> {
            checkReleases(true);
            redraw.run();
        });

        f.section("This computer");
        f.row(installed == null ? "No backend installed" : label(installed.variantId()) + ", build " + (installed.build() > 0 ? installed.build() : "unknown"),
                installedHelp(latest, update), check);
        if (update && !installer.isBusy()) {
            Offer offer = latest.offer();
            ButtonOverlay button = Components.accentButton("Update", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
            button.onAction(() -> install(offer));
            f.row("Update available", "Build " + offer.build() + (offer.published().isEmpty() ? "" : ", released " + offer.published())
                    + ", " + ModelLibrary.formatSize(offer.size()) + ". Chat picks up the new one on the next message.", button);
        }
        f.endCard();

        if (checkError != null) f.place(warn(checkError, f.width, 13f), 18);
    }

    private String installedHelp(Listing latest, boolean update) {
        List<String> parts = new ArrayList<>();
        if (installed == null) {
            parts.add("Chat can't run a model on this computer until one is installed. Pick one below.");
        } else if (gpus.isEmpty()) {
            parts.add(installed.variantId().equals("cpu") ? "Replies run on the processor." : "It didn't find a graphics card it can use, so replies run on the processor.");
        } else {
            parts.add("Using " + gpus.stream().map(BackendTab::deviceName).collect(Collectors.joining(", ")) + ".");
        }
        if (installed != null && !update && latest != null && latest.offer() != null && releases != null) {
            parts.add("This is the latest build" + checkedAgo() + ".");
        }
        if (gpus.isEmpty() && !devices.names().isEmpty()) {
            parts.add("This computer has " + String.join(", ", devices.names()) + ".");
        }
        return String.join(" ", parts);
    }

    // The builds

    private void available(SettingsForm f) {
        if (releases == null) {
            if (!checking) f.note("Couldn't look up the available builds yet. Use Check for updates to try again.");
            else f.note("Checking GitHub for the latest builds...");
            return;
        }

        String recommended = recommended(releases.listings());
        List<Listing> shown = new ArrayList<>();
        int hidden = 0;
        for (Listing listing : releases.listings()) {
            boolean installedOne = installed != null && installed.variantId().equals(listing.variant().id());
            if (showOthers || installedOne || compatible(listing.variant())) shown.add(listing);
            else hidden++;
        }

        f.section(showOthers ? "All builds" : "Builds for this computer");
        for (Listing listing : shown) {
            BackendVariant variant = listing.variant();
            boolean best = variant.id().equals(recommended);
            f.row(variant.label() + (best ? " - Recommended" : ""), help(listing), control(listing, best));
        }
        f.endCard();

        if (hidden > 0 || showOthers) {
            ButtonOverlay toggle = Components.secondaryButton(showOthers ? "Show fewer" : "Show " + hidden + " more", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
            toggle.onAction(() -> {
                showOthers = !showOthers;
                redraw.run();
            });
            f.place(toggle, 10);
            f.note("The others are made for hardware this computer doesn't seem to have. They're here in case the scan got it wrong.");
        }
    }

    private String help(Listing listing) {
        BackendVariant variant = listing.variant();
        Offer offer = listing.offer();
        List<String> parts = new ArrayList<>();
        parts.add(variant.blurb());
        if (offer == null) {
            parts.add("Not in the latest releases.");
        } else {
            parts.add("Build " + offer.build() + ", " + (offer.size() > 0 ? ModelLibrary.formatSize(offer.size()) : "size unknown")
                    + (offer.published().isEmpty() ? "" : ", released " + offer.published()) + ".");
        }
        String problem = mismatch(variant);
        if (problem != null) parts.add(problem);
        return String.join(" ", parts);
    }

    private Element control(Listing listing, boolean best) {
        Offer offer = listing.offer();
        boolean isInstalled = installed != null && installed.variantId().equals(listing.variant().id());

        if (isInstalled && (offer == null || offer.build() <= installed.build())) {
            TextOverlay done = new TextOverlay("Installed", 13f, Components.VALID);
            done.setTextColor(Components.VALID); // The constructor's color is replaced by the theme's.
            Container box = Components.transparent(BUTTON_WIDTH, CONTROL_HEIGHT);
            done.setPosition(BUTTON_WIDTH - done.getWidth(), (CONTROL_HEIGHT - done.getHeight()) / 2.0);
            box.addElement(done);
            return box;
        }
        if (offer == null) {
            ButtonOverlay none = Components.secondaryButton("Unavailable", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
            none.setEnabled(false);
            return none;
        }
        String text = isInstalled ? "Update" : "Install";
        ButtonOverlay button = best || isInstalled
                ? Components.accentButton(text, 13f, BUTTON_WIDTH, CONTROL_HEIGHT)
                : Components.secondaryButton(text, 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
        button.setEnabled(!installer.isBusy());
        button.onAction(() -> install(offer));
        return button;
    }

    private void install(Offer offer) {
        startError = installer.start(offer);
        redraw.run();
    }

    // The download and what became of the last install

    private void progress(SettingsForm f) {
        BackendInstaller.Stage stage = installer.getStage();
        if (startError != null) f.place(warn(startError, f.width, 13f), 18);
        if (stage == BackendInstaller.Stage.IDLE) return;

        Offer offer = installer.getOffer();
        String name = offer == null ? "backend" : offer.variant().label() + " " + offer.tag();
        switch (stage) {
            case DOWNLOADING -> {
                f.section("Downloading " + name);
                double barWidth = f.width - 2 * CARD_PADDING;
                for (Download download : installer.manager().getDownloads()) {
                    DownloadBarContainer bar = new DownloadBarContainer(download, barWidth);
                    bar.setManager(installer.manager());
                    f.custom(bar);
                    if (download.getState() == DownloadState.FAILED && download.getError() != null) {
                        f.custom(warn(String.valueOf(download.getError().getMessage()), barWidth, 12f));
                    }
                }
                f.endCard();
            }
            case INSTALLING -> {
                f.section("Installing " + name);
                f.custom(progressBar(f.width - 2 * CARD_PADDING, installer.getStep(), installer.getStepProgress()));
                f.endCard();
            }
            case DONE, FAILED -> {
                boolean failed = stage == BackendInstaller.Stage.FAILED;
                ButtonOverlay dismiss = Components.secondaryButton("Dismiss", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
                dismiss.onAction(installer::acknowledge);
                String note = installer.getMessage();
                f.section(failed ? "Install failed" : "Install finished");
                f.row(failed ? "Nothing was changed" : name + " is installed", failed || !note.isEmpty() ? note : "Chat uses it from the next message.", dismiss);
                f.endCard();
            }
            default -> {
            }
        }
    }

    // What suits this computer

    // The CUDA build that matches the driver if the computer has an NVIDIA card, otherwise Metal or Vulkan.
    private String recommended(List<Listing> listings) {
        if (devices.has(Vendor.NVIDIA)) {
            int best = 0;
            String id = null;
            for (Listing listing : listings) {
                int major = listing.variant().cudaMajor();
                if (listing.offer() != null && major > best && fitsDriver(listing.variant())) {
                    best = major;
                    id = listing.variant().id();
                }
            }
            if (id != null) return id;
        }
        if (devices.has(Vendor.APPLE)) return "metal";
        if (devices.has(Vendor.NVIDIA) || devices.has(Vendor.AMD) || devices.has(Vendor.INTEL)) {
            return listings.stream().anyMatch(l -> l.variant().id().equals("vulkan") && l.offer() != null) ? "vulkan" : null;
        }
        return null;
    }

    private boolean compatible(BackendVariant variant) {
        return mismatch(variant) == null;
    }

    // Why the build isn't for this computer, or null if it is (or the scan couldn't tell).
    private String mismatch(BackendVariant variant) {
        if (variant.vendor() == Vendor.NONE || devices.vendors().isEmpty()) return null;
        if (!devices.has(variant.vendor())) return "No " + vendorName(variant.vendor()) + " graphics card was found on this computer.";
        if (!fitsDriver(variant)) return "The NVIDIA driver here only supports CUDA " + devices.cudaVersion() + ".";
        return null;
    }

    // A driver runs CUDA builds up to its own version. If it didn't say, any is worth a try.
    private boolean fitsDriver(BackendVariant variant) {
        return variant.cudaMajor() == 0 || devices.cudaMajor() == 0 || variant.cudaMajor() <= devices.cudaMajor();
    }

    private static String vendorName(Vendor vendor) {
        return switch (vendor) {
            case APPLE -> "Apple";
            case NVIDIA -> "NVIDIA";
            case AMD -> "AMD";
            case INTEL -> "Intel";
            case NONE -> "";
        };
    }

    // Background work

    // The installed backend and the graphics cards, both of which start a process to find out.
    private void readComputer() {
        Thread.ofVirtual().name("backend-scan").start(() -> {
            scanning("Reading the installed backend", 0);
            InstalledBackend found = InstalledBackend.read(App.environment.getDataPath("backend"));
            scanning("Looking for graphics cards", 1 / 3.0);
            DeviceScan.Result scan = DeviceScan.get();
            scanning("Asking the backend which devices it can use", 2 / 3.0);
            List<HardwareProbe.Device> probed = HardwareProbe.gpus();
            Scheduler.runLater(() -> {
                if (closed) return;
                installed = found;
                installedRead = true;
                devices = scan;
                gpus = probed;
                redraw.run();
            });
        });
    }

    // Only the first look shows a bar, a look after an install keeps showing what was there.
    private void scanning(String step, double progress) {
        Scheduler.runLater(() -> {
            scanStep = step;
            scanProgress = progress;
            if (!closed && !installedRead) redraw.run();
        });
    }

    private void checkReleases(boolean force) {
        checking = true;
        checkError = null;
        Thread.ofVirtual().name("backend-releases").start(() -> {
            BackendReleases.Result result = null;
            String failure = null;
            try {
                result = BackendReleases.fetch(force);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                App.logger.warn("Could not check for llama.cpp builds", e);
                failure = e instanceof IOException && e.getMessage() != null && e.getMessage().contains("HTTP 403")
                        ? "GitHub is limiting how often builds can be looked up from this connection. Try again in a little while."
                        : "Couldn't reach GitHub to look for builds. Check your connection and try again.";
            }
            BackendReleases.Result fetched = result;
            String message = failure;
            Scheduler.runLater(() -> {
                if (closed) return;
                checking = false;
                checkError = message;
                if (fetched != null) releases = fetched;
                redraw.run();
            });
        });
    }

    // Helpers

    // What's being done now, and a bar that's filled as far as the steps have got. The stripes show it hasn't stalled.
    private static Element progressBar(double width, String step, double progress) {
        Container box = Components.transparent(width, 40);
        TextOverlay label = new TextOverlay(step + "...", 13f, Components.theme().getText());
        box.addElement(label);

        ProgressBarOverlay bar = new ProgressBarOverlay(width, 8);
        bar.setFillColor(Palette.accent());
        bar.setStripes(new Color(1f, 1f, 1f, 0.25f));
        bar.setProgress(progress);
        bar.setPosition(0, 26);
        box.addElement(bar);
        return box;
    }

    // The color is set again after the constructor, which otherwise replaces it with the theme's text color.
    private static TextFlowOverlay warn(String text, double width, float size) {
        TextFlowOverlay flow = new TextFlowOverlay(text, width, size, Components.WARN);
        flow.setTextColor(Components.WARN);
        return flow;
    }

    private Listing listingFor(String variantId) {
        if (releases == null) return null;
        return releases.listings().stream().filter(l -> l.variant().id().equals(variantId)).findFirst().orElse(null);
    }

    private static String label(String variantId) {
        return BackendCatalog.forCurrentSystem().stream().filter(v -> v.id().equals(variantId)).map(BackendVariant::label).findFirst().orElse(variantId);
    }

    private static String deviceName(HardwareProbe.Device device) {
        String name = device.description().isEmpty() ? device.name() : device.description();
        return name + " (" + ModelLibrary.formatSize(device.totalMb() * 1024L * 1024L) + ")";
    }

    // " (checked 3 minutes ago)"
    private String checkedAgo() {
        long minutes = Duration.between(releases.checked(), Instant.now()).toMinutes();
        if (minutes < 1) return ", checked just now";
        return ", checked " + minutes + (minutes == 1 ? " minute ago" : " minutes ago");
    }
}
