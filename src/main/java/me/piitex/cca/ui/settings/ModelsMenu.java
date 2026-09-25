package me.piitex.cca.ui.settings;

import me.piitex.cca.App;
import me.piitex.cca.backend.model.GgufInfo;
import me.piitex.cca.backend.model.HardwareProbe;
import me.piitex.cca.backend.model.LayerPlanner;
import me.piitex.cca.backend.model.ModelDownloads;
import me.piitex.cca.backend.model.ModelLibrary;
import me.piitex.cca.backend.model.ServerArgs;
import me.piitex.cca.backend.server.CloudEndpoint;
import me.piitex.cca.backend.server.ServerProcess;
import me.piitex.cca.config.ModelSettings;
import me.piitex.cca.config.Settings.Setting;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.cca.ui.ContentMenu;
import me.piitex.cca.ui.MainMenu;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.PercentSliderOverlay;
import me.piitex.engine.ui.overlays.SpinnerOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import me.piitex.engine.ui.theme.Theme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static me.piitex.cca.backend.model.ModelLibrary.formatSize;
import static me.piitex.cca.ui.settings.SettingsUi.CARD_PADDING;
import static me.piitex.cca.ui.settings.SettingsUi.CONTROL_HEIGHT;
import static me.piitex.cca.ui.settings.SettingsUi.FOOTER_HEIGHT;
import static me.piitex.cca.ui.settings.SettingsUi.PANEL_PADDING;
import static me.piitex.cca.ui.settings.SettingsUi.TAB_HEIGHT;
import static me.piitex.cca.ui.settings.SettingsUi.TEXT_WIDTH;

/**
 * Which model to run and every llama.cpp option for it. All the tabs edit one copy of the settings ({@link #draft})
 * and nothing is used until Save. If a launch option changed the server is stopped so the next message reloads it.
 * <p>
 * Controls are rebuilt from the draft on every redraw and write straight back to it, so a redraw never loses
 * an edit. The memory plan card updates itself in place instead, since a redraw while dragging the slider would drop the drag.
 */
public class ModelsMenu implements ContentMenu {
    private final Theme theme = Components.theme();
    private final MainMenu owner;

    private static final String SHARE_WARNING = "No access key is set, so anyone on your network can use this model. Add a key above unless you trust every device on it.";

    private enum Tab implements SettingsTabs.Tab {
        MODEL("Model", Feather.BOX, "model."),
        // Nothing to save here, it has no footer.
        DOWNLOAD("Download", Feather.DOWNLOAD),
        HARDWARE("Hardware", Feather.CPU, "hardware."),
        CONTEXT("Context", Feather.LAYERS, "context.", "rope."),
        SAMPLING("Sampling", Feather.SLIDERS, "sampling."),
        SERVER("Server", Feather.SERVER, "server."),
        CLOUD("Cloud", Feather.CLOUD, "cloud.");

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

    private final ModelSettings draft = App.instance.getModelSettings().copy();
    private final ScrollMemory scrollMemory = new ScrollMemory();
    private final ModelDownloadTab download = new ModelDownloadTab(scrollMemory, this::redraw);
    // Downloads outlive this screen, so it stops listening when it closes.
    private final Runnable downloadEvent = () -> Scheduler.runLater(this::downloadsChanged);
    private Tab activeTab = Tab.MODEL;
    private boolean closed;

    private List<ModelLibrary.Entry> models = ModelLibrary.list();

    // The selected model's header, read on another thread. infoFor is the file it's for so a slow read
    // for a model they already moved off of is thrown away.
    private GgufInfo info;
    private Path infoFor;
    private String infoError;

    private List<HardwareProbe.Device> gpus = HardwareProbe.cachedGpus();

    // 00001-of-00005 at the end of a file name means the model is split into parts.
    private static final Pattern SHARD = Pattern.compile("^(.*)-\\d{5}-of-\\d{5}\\.gguf$", Pattern.CASE_INSENSITIVE);

    private String savedMessage;
    private String addMessage;
    private String cloudTestMessage = "";

    // The parts of the plan card that update in place. Null while the Hardware tab isn't showing.
    private TextOverlay planPercent;
    private TextOverlay planLayers, planGpu, planSystem, planCost, planCaption, planWarning;
    private Container planBarFill;
    private double planBarWidth;

    // Same for the sharing address on the Server tab.
    private TextOverlay shareAddress;
    private TextFlowOverlay shareWarning;

    private SettingsFooter footer;

    public ModelsMenu(MainMenu owner) {
        this.owner = owner;
        loadInfo();
        if (gpus == null) probeGpus(false);
        ModelDownloads.get().addListener(downloadEvent);
    }

    @Override
    public String getTitle() {
        return "Models";
    }

    @Override
    public void close() {
        closed = true;
        download.close();
        ModelDownloads.get().removeListener(downloadEvent);
    }

    private void redraw() {
        if (!closed) owner.renderContent();
    }

    @Override
    public Element render(double width, double height) {
        scrollMemory.capture(); // The old form is still showing until this replaces it.
        planPercent = null;
        shareAddress = null;
        footer = null;

        Container panel = Components.panel(width, height);
        double innerWidth = Math.max(0, width - 2 * PANEL_PADDING);

        Element tabs = SettingsTabs.bar(Tab.values(), activeTab, innerWidth, this::goToTab);
        tabs.setPosition(PANEL_PADDING, PANEL_PADDING);
        panel.addElement(tabs);

        double bodyY = PANEL_PADDING + TAB_HEIGHT + 20;
        // The Download tab has nothing to save, so it uses the space the footer would take.
        boolean hasFooter = activeTab != Tab.DOWNLOAD;
        double footerY = height - PANEL_PADDING - FOOTER_HEIGHT;
        double bodyHeight = Math.max(0, (hasFooter ? footerY - 16 : height - PANEL_PADDING) - bodyY);

        Element body;
        if (activeTab == Tab.DOWNLOAD) {
            body = download.build(innerWidth, bodyHeight);
        } else {
            SettingsForm form = new SettingsForm(innerWidth, bodyHeight, scrollMemory);
            switch (activeTab) {
                case MODEL -> buildModelTab(form);
                case HARDWARE -> buildHardwareTab(form);
                case CONTEXT -> buildContextTab(form);
                case SAMPLING -> buildSamplingTab(form);
                case SERVER -> buildServerTab(form);
                case CLOUD -> buildCloudTab(form);
            }
            body = form.finish();
        }
        body.setPosition(PANEL_PADDING, bodyY);
        panel.addElement(body);

        if (hasFooter) {
            footer = new SettingsFooter(innerWidth, this::save, this::discard, this::resetTab);
            footer.element().setPosition(PANEL_PADDING, footerY);
            panel.addElement(footer.element());
        }

        refreshFooter();
        refreshPlan();
        refreshShare();
        return panel;
    }

    private void goToTab(Tab tab) {
        if (tab == activeTab) return;
        activeTab = tab;
        scrollMemory.reset();
        if (tab == Tab.MODEL) models = ModelLibrary.list(); // Something may have finished downloading.
        redraw();
        if (tab == Tab.SERVER) pollServerStatus();
    }

    // Saving

    private void save() {
        ModelSettings live = App.instance.getModelSettings();
        boolean restart = ServerArgs.requiresRestart(live, draft) || draft.usesCloud() != live.usesCloud();
        live.apply(draft);
        App.logger.info("Saved model settings.");

        ServerProcess server = ServerProcess.get();
        boolean running = server.getState() == ServerProcess.State.READY || server.getState() == ServerProcess.State.STARTING;
        if (live.usesCloud() || (restart && running)) {
            server.stop();
        }
        savedMessage = restart && running && !live.usesCloud()
                ? "Saved. The model reloads with the new settings on your next message."
                : "Saved.";
        redraw();
    }

    private void discard() {
        draft.loadFrom(App.instance.getModelSettings());
        savedMessage = null;
        infoFor = null;
        loadInfo();
        redraw();
    }

    private void resetTab() {
        draft.resetGroup(activeTab.settingPrefixes());
        savedMessage = null;
        if (activeTab == Tab.MODEL) {
            infoFor = null;
            loadInfo();
        }
        redraw();
    }

    private void refreshFooter() {
        if (footer != null) footer.update(draft.differsFrom(App.instance.getModelSettings()), savedMessage);
    }

    // Every control calls this after it changes the draft.
    private void changed() {
        savedMessage = null;
        refreshFooter();
        refreshPlan();
        refreshShare();
    }

    // A download started, finished or failed. Only the tabs that show models or downloads have anything to update.
    private void downloadsChanged() {
        if (closed) return;
        models = ModelLibrary.list();
        if (activeTab == Tab.DOWNLOAD || activeTab == Tab.MODEL) redraw();
    }

    // Background work

    private void loadInfo() {
        Path model = ModelLibrary.resolve(draft);
        if (model == null) {
            info = null;
            infoFor = null;
            infoError = null;
            return;
        }
        if (model.equals(infoFor)) return;
        infoFor = model;
        info = null;
        infoError = null;
        Thread.ofVirtual().name("gguf-read").start(() -> {
            GgufInfo read = null;
            String error = null;
            try {
                read = GgufInfo.read(model);
            } catch (IOException e) {
                App.logger.warn("Could not read {}", model, e);
                error = e.getMessage();
            }
            GgufInfo result = read;
            String failure = error;
            Scheduler.runLater(() -> {
                if (!model.equals(infoFor)) return;
                info = result;
                infoError = failure;
                redraw();
            });
        });
    }

    private void probeGpus(boolean refresh) {
        Thread.ofVirtual().name("gpu-probe").start(() -> {
            List<HardwareProbe.Device> found = refresh ? HardwareProbe.refresh() : HardwareProbe.gpus();
            Scheduler.runLater(() -> {
                gpus = found;
                redraw();
            });
        });
    }

    // Model tab

    private void buildModelTab(SettingsForm f) {
        f.heading("Available models");
        Path selected = ModelLibrary.resolve(draft);
        if (models.isEmpty()) {
            f.place(buildEmptyModels(f.width), 16);
        }
        for (ModelLibrary.Entry entry : models) {
            boolean isSelected = selected != null && entry.file().equals(selected);
            f.place(SettingsUi.optionRow(f.width, entry.fileName(), formatSize(entry.bytes()), isSelected, () -> selectModel(entry)).row(), 8);
        }
        Container buttons = Components.transparent(f.width, 34);
        ButtonOverlay refresh = secondaryButton("Refresh", 100, 34);
        refresh.onAction(() -> {
            models = ModelLibrary.list();
            redraw();
        });
        buttons.addElement(refresh);
        ButtonOverlay add = secondaryButton("Add model file", 150, 34);
        add.setPosition(112, 0);
        add.onAction(this::addModel);
        buttons.addElement(add);
        ButtonOverlay find = secondaryButton("Download Model", 150, 34);
        find.setPosition(274, 0);
        find.onAction(() -> goToTab(Tab.DOWNLOAD));
        buttons.addElement(find);
        f.place(buttons, addMessage == null ? 24 : 8);
        if (addMessage != null) f.note(addMessage);

        f.section("Selected model");
        if (selected == null) {
            f.row("No model selected", "Choose one from the list above.", value(""));
        } else if (info == null && infoError == null) {
            f.row("Reading model...", null, value(""));
        } else if (infoError != null) {
            f.row("Couldn't read this file", infoError, value(""));
        } else {
            f.row("Name", null, value(info.name()));
            f.row("Architecture", null, value(info.architecture() + (info.expertModel() ? " (mixture of experts)" : "")));
            f.row("Quantization", null, value(info.quantization().isEmpty() ? "Unknown" : info.quantization()));
            f.row("File size", null, value(formatSize(info.fileBytes())));
            f.row("Layers", null, value(String.valueOf(info.layers())));
            f.row("Trained context", null, value(info.trainingContext() > 0 ? String.format(Locale.ROOT, "%,d tokens", info.trainingContext()) : "Unknown"));
            f.row("Embedding size", null, value(String.valueOf(info.embeddingLength())));
        }
        f.endCard();
    }

    private Element buildEmptyModels(double width) {
        Container box = SettingsUi.card(width, 84);
        TextOverlay title = new TextOverlay("No models found", 15f, theme.getText());
        title.setPosition(CARD_PADDING, 18);
        box.addElement(title);
        TextFlowOverlay hint = new TextFlowOverlay("Add a .gguf file with the button below, or find one on the Download tab.",
                Math.max(0, width - 2 * CARD_PADDING), 12f, theme.getPlaceholderText());
        hint.setPosition(CARD_PADDING, 18 + title.getHeight() + 4);
        box.addElement(hint);
        return box;
    }

    private void selectModel(ModelLibrary.Entry entry) {
        selectModel(entry.fileName());
    }

    private void selectModel(String fileName) {
        draft.modelFile.set(fileName);
        savedMessage = null;
        loadInfo();
        redraw();
    }

    // Opens in the models folder. A file from somewhere else is copied in, so it shows up in the list.
    private void addModel() {
        try {
            Files.createDirectories(ModelLibrary.directory());
        } catch (IOException e) {
            App.logger.warn("Could not create {}", ModelLibrary.directory(), e); // The dialog falls back to its own folder.
        }
        FileDialog.open()
                .filter("GGUF model", "gguf")
                .defaultPath(ModelLibrary.directory())
                .onSelect(this::importModel)
                .show();
    }

    private void importModel(Path picked) {
        Path directory = ModelLibrary.directory();
        String name = picked.getFileName().toString();
        if (picked.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize()) || Files.exists(directory.resolve(name))) {
            models = ModelLibrary.list();
            addMessage = null;
            selectModel(name);
            return;
        }

        // The other parts of a split model have to sit next to the first one.
        List<Path> files = new ArrayList<>();
        Matcher shard = SHARD.matcher(name);
        if (shard.matches()) {
            try (Stream<Path> siblings = Files.list(picked.toAbsolutePath().getParent())) {
                siblings.filter(p -> p.getFileName().toString().startsWith(shard.group(1) + "-") && SHARD.matcher(p.getFileName().toString()).matches())
                        .sorted().forEach(files::add);
            } catch (IOException e) {
                App.logger.warn("Could not look for the other parts of {}", name, e);
            }
        }
        if (files.isEmpty()) files.add(picked);

        addMessage = "Copying " + name + "...";
        redraw();
        Thread.ofVirtual().name("model-copy").start(() -> {
            String failure = null;
            try {
                for (Path file : files) {
                    Path target = directory.resolve(file.getFileName().toString());
                    // A half copied file mustn't show up as a model.
                    Path part = target.resolveSibling(target.getFileName() + ".part");
                    Files.copy(file, part, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                App.logger.error("Could not copy {} into the models folder", name, e);
                failure = "Couldn't copy " + name + ": " + e.getMessage();
            }
            String error = failure;
            Scheduler.runLater(() -> {
                models = ModelLibrary.list();
                addMessage = error;
                if (error == null) {
                    selectModel(name);
                } else {
                    redraw();
                }
            });
        });
    }

    // Hardware tab

    private void buildHardwareTab(SettingsForm f) {
        f.place(buildPlanCard(f.width), 22);

        f.section("GPU");
        List<HardwareProbe.Device> devices = gpus == null ? List.of() : gpus;
        String detected = gpus == null ? "Detecting..." : devices.isEmpty()
                ? "None found. The model runs on the CPU unless you enter the GPU memory below."
                : String.join("\n", devices.stream().map(d -> d.name() + ": " + d.description() + " (" + formatSize(d.totalMb() * 1024 * 1024) + ")").toList());
        ButtonOverlay redetect = secondaryButton("Re-detect", 110, CONTROL_HEIGHT);
        redetect.onAction(() -> {
            gpus = null;
            redraw();
            probeGpus(true);
        });
        f.row("Detected GPUs", detected, redetect);

        List<String> deviceNames = new ArrayList<>();
        deviceNames.add("");
        devices.forEach(d -> deviceNames.add(d.name()));
        f.row("Device", "Use every GPU, or pin the model to a single one.",
                choice(draft.device, deviceNames, name -> name.isEmpty() ? "All GPUs (auto)" : name));
        f.row("GPU memory override (MB)", "Total GPU memory to plan with. 0 uses what was detected.",
                intSpinner(draft.gpuMemoryMb, 0, 1_048_576, 256));
        f.row("GPU headroom (MB)", "Memory always left free for compute buffers and the desktop. Lower it to squeeze in more layers.",
                intSpinner(draft.gpuHeadroomMb, 0, 65_536, 64));
        f.row("KV cache on GPU", "Keeps the conversation cache in GPU memory with the layers it belongs to. Turn off to save GPU memory at some speed cost.",
                toggle(draft.kvOffload));
        f.row("Split mode", "How the model is divided when there is more than one GPU.",
                choice(draft.splitMode, List.of("layer", "none", "row", "tensor"), Function.identity()));
        f.row("Main GPU", "Which GPU holds the model when splitting is off (its index, starting at 0).",
                intSpinner(draft.mainGpu, 0, 64, 1));
        f.row("Tensor split", "Proportion of the model per GPU, e.g. 3,1. Blank splits by free memory.",
                text(draft.tensorSplit, "auto", 160));

        f.section("CPU and memory");
        f.row("Threads", "CPU threads for generation. -1 lets llama.cpp choose.", intSpinner(draft.threads, -1, 512, 1));
        f.row("Batch threads", "CPU threads for reading the prompt. -1 uses the same as Threads.", intSpinner(draft.threadsBatch, -1, 512, 1));
        f.row("Priority", "Process priority for the server.",
                choice(draft.priority, List.of(-1, 0, 1, 2, 3), p -> switch (p) {
                    case -1 -> "Low";
                    case 1 -> "Medium";
                    case 2 -> "High";
                    case 3 -> "Realtime";
                    default -> "Normal";
                }));
        f.row("Model loading", "How the file is read. Memory-map (auto) starts fastest; lock keeps it from being swapped out; direct I/O skips the file cache.",
                choice(draft.loadMode, List.of("auto", "none", "mmap", "mlock", "mmap+mlock", "dio"), Function.identity()));
        f.row("Flash attention", "Faster, lower-memory attention. Required for a quantized V cache.",
                choice(draft.flashAttn, List.of("auto", "on", "off"), Function.identity()));
        f.row("Expert weights on CPU (layers)", "For mixture-of-experts models: keep the expert weights of the first N layers in system memory.",
                intSpinner(draft.cpuMoeLayers, 0, 1024, 1));
        f.row("NUMA", "Optimizations for multi-socket machines.",
                choice(draft.numa, List.of("", "distribute", "isolate", "numactl"), n -> n.isEmpty() ? "Off" : n));
        f.endCard();
    }

    // The GPU usage slider and what it works out to. Updated in place by refreshPlan.
    private Element buildPlanCard(double width) {
        double inner = Math.max(0, width - 2 * CARD_PADDING);
        Container card = SettingsUi.card(width, 0);
        double y = 18;

        TextOverlay title = new TextOverlay("GPU memory usage", 15f, theme.getText());
        title.setPosition(CARD_PADDING, y);
        card.addElement(title);

        planPercent = new TextOverlay("", 22f, Palette.accent());
        planPercent.setPosition(CARD_PADDING + inner - 60, y - 3);
        card.addElement(planPercent);
        y += title.getHeight() + 6;

        TextFlowOverlay explain = new TextFlowOverlay(
                "How much of your GPU's memory the model may use. The layers that fit are loaded onto the GPU and the rest run from system memory.",
                inner - 70, 12f, theme.getPlaceholderText());
        explain.setPosition(CARD_PADDING, y);
        card.addElement(explain);
        y += explain.getHeight() + 14;

        PercentSliderOverlay slider = new PercentSliderOverlay(inner, draft.gpuUsage.get());
        slider.setLabelFormatter(null);
        slider.setFillColor(Palette.accent());
        slider.setThumbColor(Palette.accent());
        slider.setThumbActiveColor(Palette.accentHover());
        slider.onValueChange(v -> {
            draft.gpuUsage.set((int) Math.round(v));
            changed();
        });
        slider.setPosition(CARD_PADDING, y);
        card.addElement(slider);
        y += slider.getHeight() + 10;

        double barHeight = 10;
        planBarWidth = inner;
        Container track = new Container(inner, barHeight);
        Components.fill(track, theme.getContainerBorder());
        track.getStyling().setBorderThickness(0f);
        track.getStyling().setCornerRadius((float) (barHeight / 2));
        track.setPosition(CARD_PADDING, y);
        planBarFill = new Container(0, barHeight);
        Components.fill(planBarFill, Palette.accent());
        planBarFill.getStyling().setBorderThickness(0f);
        planBarFill.getStyling().setCornerRadius((float) (barHeight / 2));
        track.addElement(planBarFill);
        card.addElement(track);
        y += barHeight + 18;

        double columnWidth = inner / 4;
        String[] labels = {"On GPU", "GPU memory", "System memory", "Cost per layer"};
        TextOverlay[] values = new TextOverlay[4];
        for (int i = 0; i < 4; i++) {
            TextOverlay label = new TextOverlay(labels[i], 12f, theme.getPlaceholderText());
            label.setPosition(CARD_PADDING + i * columnWidth, y);
            card.addElement(label);
            values[i] = new TextOverlay("-", 16f, theme.getText());
            values[i].setPosition(CARD_PADDING + i * columnWidth, y + label.getHeight() + 3);
            card.addElement(values[i]);
        }
        planLayers = values[0];
        planGpu = values[1];
        planSystem = values[2];
        planCost = values[3];
        y += 12 + 3 + 16 + 12 + 14;

        planCaption = new TextFlowOverlay("", inner, 12f, theme.getPlaceholderText());
        planCaption.setPosition(CARD_PADDING, y);
        card.addElement(planCaption);
        planWarning = new TextFlowOverlay("", inner, 12f, Components.WARN);
        planWarning.setPosition(CARD_PADDING, y + 34);
        card.addElement(planWarning);
        y += 34 + 34 + 12;

        card.setHeight(y);
        return card;
    }

    private long gpuMemoryMb() {
        return HardwareProbe.gpuMemoryMb(draft, gpus == null ? List.of() : gpus);
    }

    // Null when there's no readable model or no known GPU memory.
    private LayerPlanner.Plan currentPlan() {
        long gpuMb = gpuMemoryMb();
        if (info == null || gpuMb <= 0) return null;
        return LayerPlanner.plan(info, draft, gpuMb);
    }

    private void refreshPlan() {
        if (planPercent == null) return;
        planPercent.setText(draft.gpuUsage.get() + "%");

        if (info == null) {
            setPlan("-", "-", "-", "-", infoError != null ? "This model file couldn't be read." : "Choose a model on the Model tab to see the plan.", "", 0);
            return;
        }
        long gpuMb = gpuMemoryMb();
        if (gpuMb <= 0) {
            setPlan("CPU only", "-", formatSize(info.fileBytes()), "-",
                    gpus == null ? "Detecting your GPU..." : "No GPU memory is known, so the whole model runs on the CPU. Enter the GPU memory below if you have one.",
                    "", 0);
            return;
        }

        LayerPlanner.Plan plan = LayerPlanner.plan(info, draft, gpuMb);
        if (plan == null) {
            setPlan("-", "-", "-", "-", "This model doesn't report a layer count, so llama.cpp will place it itself.", "", 0);
            return;
        }

        long ram = HardwareProbe.systemMemoryMb() * 1024 * 1024;
        boolean kvOnGpu = draft.kvOffload.get();
        double gpuShare = plan.gpuBytes() + plan.systemBytes() == 0 ? 0 : plan.gpuBytes() / (double) (plan.gpuBytes() + plan.systemBytes());

        String caption = String.format(Locale.ROOT, "Budget %s (%d%% of %s). Weights %s plus KV cache %s at %,d tokens%s.",
                formatSize(plan.budgetBytes()), draft.gpuUsage.get(), formatSize(plan.gpuMemoryBytes()),
                formatSize(info.fileBytes()), formatSize(plan.kvTotalBytes()), draft.contextSize.get(),
                plan.kvEstimated() ? " (estimated)" : "");

        String warning = "";
        long requested = (long) (plan.gpuMemoryBytes() * draft.gpuUsage.get() / 100.0);
        if (ram > 0 && plan.systemBytes() > ram * 0.9) {
            warning = "The system-memory share is about as large as this machine's RAM. Raise GPU usage, lower the context size, or pick a smaller model.";
        } else if (plan.budgetBytes() < requested) {
            warning = "Capped by the GPU headroom setting, so a little memory stays free for the GPU's own buffers.";
        } else if (draft.gpuUsage.get() > 0 && plan.gpuLayerArg() == 0) {
            warning = "The budget is too small to hold even the output layer, so everything runs on the CPU.";
        }

        setPlan(plan.gpuLayers() + " / " + plan.totalLayers(),
                formatSize(plan.gpuBytes()) + " of " + formatSize(plan.gpuMemoryBytes()),
                formatSize(plan.systemBytes()) + (ram > 0 ? " of " + formatSize(ram) : ""),
                Math.round(plan.costPerLayerBytes(kvOnGpu) / 1048576.0) + " MB",
                caption, warning, gpuShare);
    }

    private void setPlan(String layers, String gpu, String system, String cost, String caption, String warning, double gpuShare) {
        planLayers.setText(layers);
        planGpu.setText(gpu);
        planSystem.setText(system);
        planCost.setText(cost);
        planCaption.setText(caption);
        planWarning.setText(warning);
        planBarFill.setWidth(Math.max(0, Math.min(1, gpuShare)) * planBarWidth);
    }

    // Context tab

    private void buildContextTab(SettingsForm f) {
        f.section("Context window");
        String trained = info != null && info.trainingContext() > 0 ? String.format(Locale.ROOT, " This model was trained for %,d.", info.trainingContext()) : "";
        f.row("Context size (tokens)", "How much of the conversation the model can see at once. Larger uses more memory." + trained,
                intSpinner(draft.contextSize, 256, 4_194_304, 256));
        f.row("Response length (tokens)", "The most a single reply may use. Also reserved from the context when history is trimmed.",
                intSpinner(draft.responseTokens, 16, 131_072, 16));
        f.row("Batch size", "Most prompt tokens processed per step.", intSpinner(draft.batchSize, 1, 65_536, 128));
        f.row("Micro-batch size", "Most tokens sent to the GPU in one go. Lower it if memory runs out while reading long prompts.",
                intSpinner(draft.ubatchSize, 1, 65_536, 64));

        List<String> cacheTypes = List.of("f16", "bf16", "f32", "q8_0", "q5_1", "q5_0", "q4_1", "q4_0", "iq4_nl");
        f.section("KV cache");
        f.row("Key cache type", "Precision of the cached keys. Quantized types use much less memory.",
                choice(draft.cacheTypeK, cacheTypes, Function.identity()));
        f.row("Value cache type", "Precision of the cached values. A quantized value cache needs flash attention.",
                choice(draft.cacheTypeV, cacheTypes, Function.identity()));
        f.row("Full sliding-window cache", "Keep a full-size cache for models with sliding-window attention. Uses more memory.", toggle(draft.swaFull));
        f.row("Context shift", "Drop the oldest tokens instead of stopping when the context fills.", toggle(draft.contextShift));
        f.row("Prompt cache", "Reuse the processed conversation between messages so replies start sooner.", toggle(draft.cachePrompt));
        f.row("Cache reuse chunk", "Smallest matching chunk to reuse from the cache by shifting. 0 turns it off.",
                intSpinner(draft.cacheReuse, 0, 65_536, 64));

        f.section("RoPE scaling");
        f.row("Scaling method", "How the context is stretched beyond the trained length. Model default leaves it to the file.",
                choice(draft.ropeScaling, List.of("", "none", "linear", "yarn"), s -> s.isEmpty() ? "Model default" : s));
        f.row("Scale factor", "Expands the context by this factor. 0 leaves it unset.", decSpinner(draft.ropeScale, 0, 128, 0.25, 2));
        f.row("Frequency base", "NTK-aware base frequency. 0 uses the model's own.", decSpinner(draft.ropeFreqBase, 0, 100_000_000, 1000, 0));
        f.row("Frequency scale", "Scales frequencies by 1/N. 0 leaves it unset.", decSpinner(draft.ropeFreqScale, 0, 128, 0.05, 2));
        f.row("YaRN original context", "The model's original training context for YaRN. 0 reads it from the model.",
                intSpinner(draft.yarnOrigCtx, 0, 4_194_304, 1024));
        f.row("YaRN extrapolation mix", "-1 uses the default.", decSpinner(draft.yarnExtFactor, -1, 10, 0.1, 2));
        f.row("YaRN attention factor", "-1 uses the default.", decSpinner(draft.yarnAttnFactor, -1, 10, 0.1, 2));
        f.row("YaRN slow beta", "-1 uses the default.", decSpinner(draft.yarnBetaSlow, -1, 100, 0.1, 2));
        f.row("YaRN fast beta", "-1 uses the default.", decSpinner(draft.yarnBetaFast, -1, 100, 0.1, 2));
        f.endCard();
    }

    // Sampling tab

    private void buildSamplingTab(SettingsForm f) {
        f.note("Sampling controls how the model picks each word. These are sent with every message, so changes apply immediately - no reload.");

        f.section("Core");
        f.row("Temperature", "Higher is more varied and creative; lower is more predictable.", decSpinner(draft.temperature, 0, 5, 0.05, 2));
        f.row("Top-K", "Only consider the K most likely words. 0 turns it off.", intSpinner(draft.topK, 0, 1000, 1));
        f.row("Top-P", "Only consider the smallest set of words that adds up to this probability. 1 turns it off.", decSpinner(draft.topP, 0, 1, 0.01, 2));
        f.row("Min-P", "Drop words less likely than this fraction of the best one. 0 turns it off.", decSpinner(draft.minP, 0, 1, 0.01, 2));
        f.row("Typical-P", "Locally typical sampling. 1 turns it off.", decSpinner(draft.typicalP, 0, 1, 0.01, 2));
        f.row("Top-N sigma", "Keep words within N standard deviations of the best. -1 turns it off.", decSpinner(draft.topNSigma, -1, 10, 0.1, 1));
        f.row("Seed", "Fixes the randomness for repeatable replies. -1 is random.", intSpinner(draft.seed, -1, Integer.MAX_VALUE, 1));

        f.section("Repetition");
        f.row("Repeat penalty", "Discourages repeating recent words. 1 turns it off.", decSpinner(draft.repeatPenalty, 0.5, 2, 0.01, 2));
        f.row("Repeat window", "How many recent tokens the penalty looks at. 0 turns it off, -1 uses the whole context.",
                intSpinner(draft.repeatLastN, -1, 131_072, 16));
        f.row("Presence penalty", "Discourages any word already used.", decSpinner(draft.presencePenalty, -2, 2, 0.05, 2));
        f.row("Frequency penalty", "Discourages words in proportion to how often they were used.", decSpinner(draft.frequencyPenalty, -2, 2, 0.05, 2));

        f.section("DRY (repetition of phrases)");
        f.row("Multiplier", "Strength of the penalty on repeated phrases. 0 turns it off.", decSpinner(draft.dryMultiplier, 0, 5, 0.05, 2));
        f.row("Base", "How fast the penalty grows with the length of the repeat.", decSpinner(draft.dryBase, 1, 4, 0.05, 2));
        f.row("Allowed length", "Repeats up to this many tokens are not penalized.", intSpinner(draft.dryAllowedLength, 1, 256, 1));
        f.row("Window", "How many recent tokens DRY looks at. -1 uses the whole context.", intSpinner(draft.dryPenaltyLastN, -1, 131_072, 16));

        f.section("XTC (exclude top choices)");
        f.row("Probability", "Chance of skipping the most likely words. 0 turns it off.", decSpinner(draft.xtcProbability, 0, 1, 0.05, 2));
        f.row("Threshold", "Words above this probability may be skipped.", decSpinner(draft.xtcThreshold, 0, 1, 0.01, 2));

        f.section("Mirostat");
        f.row("Mode", "Steers the reply toward a target surprise. Overrides Top-K, Top-P and Typical-P when on.",
                choice(draft.mirostat, List.of(0, 1, 2), m -> m == 0 ? "Off" : m == 1 ? "Mirostat" : "Mirostat 2.0"));
        f.row("Target entropy (tau)", null, decSpinner(draft.mirostatTau, 0, 10, 0.1, 1));
        f.row("Learning rate (eta)", null, decSpinner(draft.mirostatEta, 0, 1, 0.01, 2));

        f.section("Dynamic temperature");
        f.row("Range", "Lets the temperature vary by this much around the setting. 0 turns it off.", decSpinner(draft.dynatempRange, 0, 5, 0.05, 2));
        f.row("Exponent", "How the temperature responds to the model's confidence.", decSpinner(draft.dynatempExponent, 0.1, 5, 0.1, 1));
        f.endCard();
    }

    // Server tab

    private void buildServerTab(SettingsForm f) {
        f.section("Local server");
        ButtonOverlay restart = secondaryButton("Restart", 110, CONTROL_HEIGHT);
        restart.onAction(() -> {
            ServerProcess server = ServerProcess.get();
            server.stop();
            if (!App.instance.getModelSettings().usesCloud()) server.start();
            redraw();
            pollServerStatus();
        });
        f.row("Status", serverStatusDetail(), restart);

        f.section("Share on your network");
        f.row("Share this model", "Let other devices on your home network use this computer's model as their cloud endpoint. Your firewall may ask to allow it the first time.",
                toggle(draft.shareOnNetwork));
        TextFieldOverlay serverKey = passwordField("none (open to your network)", draft.getServerApiKey(), draft::setServerApiKey);
        f.row("Access key", "Devices must send this key to connect. Strongly recommended when sharing. Enter the same key under Cloud on the other device.", serverKey);
        shareAddress = new TextOverlay("", 14f, Palette.accent());
        Container addressBox = Components.transparent(TEXT_WIDTH, 24);
        shareAddress.setPosition(0, 2);
        addressBox.addElement(shareAddress);
        f.row("Address for other devices", "Enter this as the Endpoint URL under Cloud on the other device.", addressBox);
        // Built with the full text so the row keeps its height. refreshShare blanks it when it isn't needed.
        shareWarning = new TextFlowOverlay(SHARE_WARNING, Math.max(0, f.width - 2 * CARD_PADDING), 12f, Components.WARN);
        f.custom(shareWarning);
        f.row("Start when CCA opens", "Load the model as soon as the app starts, for a computer that only serves other devices. Leave the app running to keep it available.",
                toggle(draft.autoStart));

        f.section("Connection");
        f.row("Host", "Address to listen on. 127.0.0.1 keeps it private to this computer. Ignored while sharing on your network.", text(draft.host, "127.0.0.1", 200));
        f.row("Port", null, intSpinner(draft.port, 1, 65_535, 1));
        f.row("Request timeout (s)", "How long the server waits on a reading or writing request.", intSpinner(draft.timeoutSeconds, 1, 86_400, 60));
        f.row("Load timeout (s)", "How long to wait for the model to finish loading before giving up. Raise it for very large models.",
                intSpinner(draft.loadTimeoutSeconds, 10, 7200, 30));
        f.row("Parallel slots", "Conversations served at once. -1 lets llama.cpp choose.", intSpinner(draft.parallel, -1, 256, 1));
        f.row("Continuous batching", "Serve several requests together.", toggle(draft.contBatching));

        f.section("Chat and reasoning");
        f.row("Jinja templates", "Use the model's own chat template. Leave on unless a model misbehaves.", toggle(draft.jinja));
        f.row("Chat template", "A built-in name (chatml, llama3, ...) or a template file. Blank uses the one stored in the model.",
                withBrowse(text(draft.chatTemplate, "model default", TEXT_WIDTH - 90)));
        f.row("Reasoning", "Let reasoning models think before replying.", choice(draft.reasoning, List.of("auto", "on", "off"), Function.identity()));
        f.row("Reasoning format", "Where the model's thoughts go in the response.",
                choice(draft.reasoningFormat, List.of("auto", "none", "deepseek", "deepseek-legacy"), Function.identity()));
        f.row("Reasoning budget (tokens)", "Most tokens spent thinking. -1 is unlimited, 0 skips thinking.", intSpinner(draft.reasoningBudget, -1, 131_072, 64));

        f.section("Behavior");
        f.row("Warm-up", "Run an empty pass at startup so the first reply is quicker.", toggle(draft.warmup));
        f.row("Verbose log", "Write detailed output to server.log.", toggle(draft.verbose));
        f.row("Extra arguments", "Anything else to pass to llama-server, added last. Example: --no-mmproj", text(draft.extraArgs, "--flag value", TEXT_WIDTH));

        f.section("Command");
        Path model = ModelLibrary.resolve(draft);
        String command = String.join(" ", ServerArgs.build(Path.of("llama-server"), model != null ? model : Path.of("model.gguf"), draft, currentPlan()));
        f.custom(new TextFlowOverlay(command, Math.max(0, f.width - 2 * CARD_PADDING), 12f, theme.getPlaceholderText()));
        f.endCard();
    }

    private String serverStatusDetail() {
        if (App.instance.getModelSettings().usesCloud()) {
            return "Cloud endpoint is on, so the local server isn't used for chat.";
        }
        ServerProcess server = ServerProcess.get();
        String model = server.getModelFile() != null ? server.getModelFile().getFileName().toString() : null;
        LayerPlanner.Plan plan = server.getPlan();
        String layers = plan != null ? ", " + plan.gpuLayers() + " of " + plan.totalLayers() + " layers on the GPU" : "";
        return switch (server.getState()) {
            case STOPPED -> "Stopped. It starts with your next message. Restart uses the saved settings.";
            case STARTING -> "Loading " + (model != null ? model : "the model") + "...";
            case READY -> "Running " + model + layers + ".";
            case FAILED -> "Failed: " + server.getErrorMessage();
        };
    }

    // Redraws every second while the server is loading so the status stays current.
    private void pollServerStatus() {
        Scheduler.after(1f, () -> {
            if (closed || activeTab != Tab.SERVER) return;
            redraw();
            if (ServerProcess.get().getState() == ServerProcess.State.STARTING) pollServerStatus();
        });
    }

    private void refreshShare() {
        if (shareAddress == null) return;
        if (draft.shareOnNetwork.get()) {
            String lan = HardwareProbe.lanAddress();
            shareAddress.setText("http://" + (lan != null ? lan : "<this computer's address>") + ":" + draft.port.get());
            shareWarning.setText(draft.getServerApiKey().isEmpty() ? SHARE_WARNING : "");
        } else {
            shareAddress.setText("Not shared");
            shareWarning.setText("");
        }
    }

    private Element withBrowse(TextFieldOverlay field) {
        Container box = Components.transparent(TEXT_WIDTH, CONTROL_HEIGHT);
        field.setPosition(0, 0);
        box.addElement(field);
        ButtonOverlay browse = secondaryButton("Browse", 80, CONTROL_HEIGHT);
        browse.setPosition(TEXT_WIDTH - 80, 0);
        browse.onAction(() -> FileDialog.open()
                .filter("Jinja template", "jinja", "j2", "txt")
                .onSelect(path -> {
                    draft.chatTemplate.set(path.toString());
                    changed();
                    redraw();
                })
                .show());
        box.addElement(browse);
        return box;
    }

    // Cloud tab

    private void buildCloudTab(SettingsForm f) {
        f.note("Point chat at your own llama.cpp-compatible endpoint (for example a llama-server on another machine) instead of loading a model on this computer. "
                + "Your sampling settings are sent with every request.");

        f.section("Custom llama endpoint");
        f.row("Use cloud endpoint", "Send chat to the endpoint below. The local model isn't loaded while this is on.", toggle(draft.cloudEnabled));
        f.row("Endpoint URL", "The server's address, e.g. http://192.168.1.20:8080 or https://llama.example.com/v1.",
                text(draft.cloudUrl, "https://your-server:8080", TEXT_WIDTH));
        f.row("API key", "Sent as a bearer token. Stored encrypted on this computer. Leave blank if the endpoint is open.",
                passwordField("optional", draft.getCloudApiKey(), draft::setCloudApiKey));
        f.row("Model name", "Sent as the model in each request. Blank lets the server use whatever it has loaded.",
                text(draft.cloudModel, "optional", TEXT_WIDTH));
        f.row("Request timeout (s)", "How long to wait for the endpoint to respond.", intSpinner(draft.cloudTimeoutSeconds, 5, 3600, 15));

        ButtonOverlay test = secondaryButton("Test connection", 130, CONTROL_HEIGHT);
        test.onAction(this::testCloud);
        f.row("Connection", cloudTestMessage.isEmpty() ? "Checks that the endpoint answers and the key is accepted." : cloudTestMessage, test);
        f.endCard();
    }

    private void testCloud() {
        cloudTestMessage = "Testing...";
        redraw();
        Thread.ofVirtual().name("cloud-test").start(() -> {
            CloudEndpoint.TestResult result = CloudEndpoint.test(draft);
            App.logger.info("Cloud endpoint test: {}", result.message());
            Scheduler.runLater(() -> {
                cloudTestMessage = result.message();
                redraw();
            });
        });
    }

    // Controls

    private TextFieldOverlay passwordField(String placeholder, String value, Consumer<String> setter) {
        TextFieldOverlay field = new TextFieldOverlay(TEXT_WIDTH, CONTROL_HEIGHT);
        field.setPlaceholder(placeholder);
        field.setPassword(true);
        field.setText(value);
        field.onTextChanged(t -> {
            setter.accept(t);
            changed();
        });
        return field;
    }

    private SpinnerOverlay intSpinner(Setting<Integer> setting, int min, int max, int step) {
        return SettingsUi.intSpinner(setting, min, max, step, this::changed);
    }

    private SpinnerOverlay decSpinner(Setting<Double> setting, double min, double max, double step, int decimals) {
        return SettingsUi.decSpinner(setting, min, max, step, decimals, this::changed);
    }

    private ToggleSwitchOverlay toggle(Setting<Boolean> setting) {
        return SettingsUi.toggle(setting, this::changed);
    }

    private <T> DropdownOverlay<T> choice(Setting<T> setting, List<T> values, Function<T, String> label) {
        return SettingsUi.choice(setting, values, label, this::changed);
    }

    private TextFieldOverlay text(Setting<String> setting, String placeholder, double width) {
        return SettingsUi.text(setting, placeholder, width, this::changed);
    }

    private TextOverlay value(String text) {
        return SettingsUi.value(text);
    }

    private ButtonOverlay secondaryButton(String text, double width, double height) {
        return Components.secondaryButton(text, 13f, width, height);
    }
}
