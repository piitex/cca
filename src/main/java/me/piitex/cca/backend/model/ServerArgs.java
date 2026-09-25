package me.piitex.cca.backend.model;

import me.piitex.cca.config.ModelSettings;
import me.piitex.cca.config.Settings.Setting;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Builds the llama-server command line. A flag is only added when the setting is different from
// llama.cpp's default. Sampling isn't here, it's sent with each chat request (see ChatCompletion).
public final class ServerArgs {

    private ServerArgs() {
    }

    /**
     * @param plan the GPU layer plan, or null if the GPU memory isn't known. Then llama.cpp picks the
     *             layer count, unless GPU usage is 0% which still forces the CPU.
     */
    public static List<String> build(Path executable, Path model, ModelSettings s, LayerPlanner.Plan plan) {
        List<String> args = new ArrayList<>();
        args.add(executable.toAbsolutePath().toString());
        args.add("-m");
        args.add(model.toAbsolutePath().toString());
        args.add("-c");
        args.add(Integer.toString(s.contextSize.get()));
        args.add("--port");
        args.add(Integer.toString(s.port.get()));
        args.add("--no-webui");

        if (!s.effectiveHost().isBlank() && !s.effectiveHost().equals(s.host.getDefault())) {
            args.add("--host");
            args.add(s.effectiveHost());
        }

        // Layers. When we already decided the count there's nothing for --fit to do, and skipping it starts faster.
        if (plan != null) {
            args.add("-ngl");
            args.add(Integer.toString(plan.gpuLayerArg()));
            args.add("-fit");
            args.add("off");
        } else if (s.gpuUsage.get() == 0) {
            args.add("-ngl");
            args.add("0");
        }
        text(args, "-dev", s.device);
        if (!s.splitMode.isDefault()) text(args, "-sm", s.splitMode);
        differs(args, "-mg", s.mainGpu);
        text(args, "-ts", s.tensorSplit);
        if (!s.kvOffload.get()) args.add("--no-kv-offload");
        if (!s.loadMode.isDefault()) text(args, "-lm", s.loadMode);
        if (!s.flashAttn.isDefault()) text(args, "-fa", s.flashAttn);
        differs(args, "-t", s.threads);
        differs(args, "-tb", s.threadsBatch);
        differs(args, "--prio", s.priority);
        if (s.cpuMoeLayers.get() > 0) {
            args.add("-ncmoe");
            args.add(Integer.toString(s.cpuMoeLayers.get()));
        }
        text(args, "--numa", s.numa);

        // Context
        differs(args, "-b", s.batchSize);
        differs(args, "-ub", s.ubatchSize);
        if (!s.cacheTypeK.isDefault()) text(args, "-ctk", s.cacheTypeK);
        if (!s.cacheTypeV.isDefault()) text(args, "-ctv", s.cacheTypeV);
        if (s.swaFull.get()) args.add("--swa-full");
        if (s.contextShift.get()) args.add("--context-shift");
        if (!s.cachePrompt.get()) args.add("--no-cache-prompt");
        if (s.cacheReuse.get() > 0) {
            args.add("--cache-reuse");
            args.add(Integer.toString(s.cacheReuse.get()));
        }

        // RoPE
        text(args, "--rope-scaling", s.ropeScaling);
        positive(args, "--rope-scale", s.ropeScale);
        positive(args, "--rope-freq-base", s.ropeFreqBase);
        positive(args, "--rope-freq-scale", s.ropeFreqScale);
        if (s.yarnOrigCtx.get() > 0) {
            args.add("--yarn-orig-ctx");
            args.add(Integer.toString(s.yarnOrigCtx.get()));
        }
        differs(args, "--yarn-ext-factor", s.yarnExtFactor);
        differs(args, "--yarn-attn-factor", s.yarnAttnFactor);
        differs(args, "--yarn-beta-slow", s.yarnBetaSlow);
        differs(args, "--yarn-beta-fast", s.yarnBetaFast);

        // Server
        differs(args, "-np", s.parallel);
        if (!s.contBatching.get()) args.add("--no-cont-batching");
        if (!s.jinja.get()) args.add("--no-jinja");
        chatTemplate(args, s.chatTemplate.get());
        if (!s.reasoning.isDefault()) text(args, "-rea", s.reasoning);
        if (!s.reasoningFormat.isDefault()) text(args, "--reasoning-format", s.reasoningFormat);
        differs(args, "--reasoning-budget", s.reasoningBudget);
        if (!s.warmup.get()) args.add("--no-warmup");
        differs(args, "-to", s.timeoutSeconds);
        if (s.verbose.get()) args.add("-v");

        args.addAll(splitArguments(s.extraArgs.get()));
        return args;
    }

    // True if the running server has to restart for the change to apply. Sampling and cloud don't count.
    public static boolean requiresRestart(ModelSettings before, ModelSettings after) {
        Path exe = Path.of("llama-server");
        Path model = Path.of("model.gguf");
        return !build(exe, model, before, null).equals(build(exe, model, after, null))
                || !before.modelFile.get().equals(after.modelFile.get())
                || !before.getServerApiKey().equals(after.getServerApiKey())
                || !before.gpuUsage.get().equals(after.gpuUsage.get())
                || !before.gpuMemoryMb.get().equals(after.gpuMemoryMb.get())
                || !before.gpuHeadroomMb.get().equals(after.gpuHeadroomMb.get())
                || !before.loadTimeoutSeconds.get().equals(after.loadTimeoutSeconds.get());
    }

    private static void text(List<String> args, String flag, Setting<String> setting) {
        String value = setting.get().trim();
        if (value.isEmpty()) return;
        args.add(flag);
        args.add(value);
    }

    private static void differs(List<String> args, String flag, Setting<? extends Number> setting) {
        if (setting.isDefault()) return;
        args.add(flag);
        args.add(number(setting.get()));
    }

    private static void positive(List<String> args, String flag, Setting<Double> setting) {
        if (setting.get() <= 0) return;
        args.add(flag);
        args.add(number(setting.get()));
    }

    private static String number(Number n) {
        return n instanceof Double d && d == Math.rint(d) ? Long.toString(d.longValue()) : n.toString();
    }

    // A path to a file uses --chat-template-file, anything else is a built in template name.
    private static void chatTemplate(List<String> args, String value) {
        String template = value.trim();
        if (template.isEmpty()) return;
        boolean isFile;
        try {
            isFile = Files.isRegularFile(Path.of(template));
        } catch (InvalidPathException e) {
            isFile = false;
        }
        args.add(isFile ? "--chat-template-file" : "--chat-template");
        args.add(template);
    }

    // Splits on whitespace but keeps quoted text together.
    static List<String> splitArguments(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean pending = false;
        for (char c : text.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0;
                else current.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                pending = true;
            } else if (Character.isWhitespace(c)) {
                if (pending || !current.isEmpty()) out.add(current.toString());
                current.setLength(0);
                pending = false;
            } else {
                current.append(c);
                pending = true;
            }
        }
        if (pending || !current.isEmpty()) out.add(current.toString());
        return out;
    }
}
