package me.piitex.cca.config;

import me.piitex.cca.App;
import me.piitex.cca.crypto.FileCrypter;
import me.piitex.engine.config.Config;
import me.piitex.engine.io.AppEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Everything the Models tab edits, saved to data/model.conf.
 * <p>
 * Most defaults match llama.cpp's own so ServerArgs can leave a flag off entirely while it's untouched.
 * The Models tab edits a {@link #copy()} and only applies it on save, so a half finished edit never
 * reaches a chat that's generating in the background.
 */
public final class ModelSettings extends Settings {

    // Encrypts the API keys. Null on the unsaved copies.
    private FileCrypter crypter;

    // The .gguf file to load. Blank means the first model found in models/.
    public final Setting<String> modelFile = str("model.file", "");

    // Hardware
    // Percent of GPU memory the model can use. LayerPlanner turns this into a layer count.
    public final Setting<Integer> gpuUsage = num("hardware.gpuUsage", 100);
    // Manual GPU memory in MB for when detection is wrong. 0 = detect.
    public final Setting<Integer> gpuMemoryMb = num("hardware.gpuMemoryMb", 0);
    // Always left free for compute buffers and the desktop.
    public final Setting<Integer> gpuHeadroomMb = num("hardware.gpuHeadroomMb", 512);
    // A llama.cpp device name like CUDA0, blank uses every GPU.
    public final Setting<String> device = str("hardware.device", "");
    public final Setting<String> splitMode = str("hardware.splitMode", "layer");
    public final Setting<Integer> mainGpu = num("hardware.mainGpu", 0);
    public final Setting<String> tensorSplit = str("hardware.tensorSplit", "");
    public final Setting<Boolean> kvOffload = bool("hardware.kvOffload", true);
    public final Setting<String> loadMode = str("hardware.loadMode", "auto");
    public final Setting<String> flashAttn = str("hardware.flashAttn", "auto");
    public final Setting<Integer> threads = num("hardware.threads", -1);
    public final Setting<Integer> threadsBatch = num("hardware.threadsBatch", -1);
    public final Setting<Integer> priority = num("hardware.priority", 0);
    public final Setting<Integer> cpuMoeLayers = num("hardware.cpuMoeLayers", 0);
    public final Setting<String> numa = str("hardware.numa", "");

    // Context
    public final Setting<Integer> contextSize = num("context.size", 4096);
    public final Setting<Integer> responseTokens = num("context.responseTokens", 512);
    public final Setting<Integer> batchSize = num("context.batchSize", 2048);
    public final Setting<Integer> ubatchSize = num("context.ubatchSize", 512);
    public final Setting<String> cacheTypeK = str("context.cacheTypeK", "f16");
    public final Setting<String> cacheTypeV = str("context.cacheTypeV", "f16");
    public final Setting<Boolean> swaFull = bool("context.swaFull", false);
    public final Setting<Boolean> contextShift = bool("context.contextShift", false);
    public final Setting<Boolean> cachePrompt = bool("context.cachePrompt", true);
    public final Setting<Integer> cacheReuse = num("context.cacheReuse", 0);
    public final Setting<String> ropeScaling = str("rope.scaling", "");
    public final Setting<Double> ropeScale = dec("rope.scale", 0.0);
    public final Setting<Double> ropeFreqBase = dec("rope.freqBase", 0.0);
    public final Setting<Double> ropeFreqScale = dec("rope.freqScale", 0.0);
    public final Setting<Integer> yarnOrigCtx = num("rope.yarnOrigCtx", 0);
    public final Setting<Double> yarnExtFactor = dec("rope.yarnExtFactor", -1.0);
    public final Setting<Double> yarnAttnFactor = dec("rope.yarnAttnFactor", -1.0);
    public final Setting<Double> yarnBetaSlow = dec("rope.yarnBetaSlow", -1.0);
    public final Setting<Double> yarnBetaFast = dec("rope.yarnBetaFast", -1.0);

    // Sampling (sent with every request, no restart needed)
    public final Setting<Double> temperature = dec("sampling.temperature", 0.8);
    public final Setting<Integer> topK = num("sampling.topK", 40);
    public final Setting<Double> topP = dec("sampling.topP", 0.95);
    public final Setting<Double> minP = dec("sampling.minP", 0.05);
    public final Setting<Double> typicalP = dec("sampling.typicalP", 1.0);
    public final Setting<Double> topNSigma = dec("sampling.topNSigma", -1.0);
    public final Setting<Double> repeatPenalty = dec("sampling.repeatPenalty", 1.1);
    public final Setting<Integer> repeatLastN = num("sampling.repeatLastN", 256);
    public final Setting<Double> presencePenalty = dec("sampling.presencePenalty", 0.0);
    public final Setting<Double> frequencyPenalty = dec("sampling.frequencyPenalty", 0.0);
    public final Setting<Double> dryMultiplier = dec("sampling.dryMultiplier", 0.0);
    public final Setting<Double> dryBase = dec("sampling.dryBase", 1.75);
    public final Setting<Integer> dryAllowedLength = num("sampling.dryAllowedLength", 2);
    public final Setting<Integer> dryPenaltyLastN = num("sampling.dryPenaltyLastN", 64);
    public final Setting<Double> xtcProbability = dec("sampling.xtcProbability", 0.0);
    public final Setting<Double> xtcThreshold = dec("sampling.xtcThreshold", 0.1);
    public final Setting<Integer> mirostat = num("sampling.mirostat", 0);
    public final Setting<Double> mirostatTau = dec("sampling.mirostatTau", 5.0);
    public final Setting<Double> mirostatEta = dec("sampling.mirostatEta", 0.1);
    public final Setting<Double> dynatempRange = dec("sampling.dynatempRange", 0.0);
    public final Setting<Double> dynatempExponent = dec("sampling.dynatempExponent", 1.0);
    public final Setting<Integer> seed = num("sampling.seed", -1);

    // Server
    public final Setting<String> host = str("server.host", "127.0.0.1");
    public final Setting<Integer> port = num("server.port", 8187);
    // Listen on every interface so other devices at home can use this computer as their cloud endpoint.
    public final Setting<Boolean> shareOnNetwork = bool("server.shareOnNetwork", false);
    // Load the model as soon as the app opens.
    public final Setting<Boolean> autoStart = bool("server.autoStart", false);
    public final Setting<Integer> parallel = num("server.parallel", -1);
    public final Setting<Boolean> contBatching = bool("server.contBatching", true);
    public final Setting<Boolean> jinja = bool("server.jinja", true);
    // A built in template name (chatml) or the path to a jinja file. Blank uses the model's template.
    public final Setting<String> chatTemplate = str("server.chatTemplate", "");
    public final Setting<String> reasoning = str("server.reasoning", "auto");
    public final Setting<String> reasoningFormat = str("server.reasoningFormat", "auto");
    public final Setting<Integer> reasoningBudget = num("server.reasoningBudget", -1);
    public final Setting<Boolean> warmup = bool("server.warmup", true);
    public final Setting<Integer> timeoutSeconds = num("server.timeoutSeconds", 3600);
    public final Setting<Integer> loadTimeoutSeconds = num("server.loadTimeoutSeconds", 120);
    public final Setting<Boolean> verbose = bool("server.verbose", false);
    // Raw arguments added last, for anything the tab doesn't have a control for.
    public final Setting<String> extraArgs = str("server.extraArgs", "");

    // Cloud
    public final Setting<Boolean> cloudEnabled = bool("cloud.enabled", false);
    public final Setting<String> cloudUrl = str("cloud.url", "");
    public final Setting<String> cloudModel = str("cloud.model", "");
    public final Setting<Integer> cloudTimeoutSeconds = num("cloud.timeoutSeconds", 120);
    private String cloudApiKey = "";
    private String serverApiKey = "";
    // The Download tab has no Save button, so this saves as it's typed. Left out of copy, compare and reset
    // so a draft that was copied earlier can't put an old one back.
    private String huggingFaceToken = "";

    private ModelSettings() {
    }

    public static ModelSettings load(AppEnvironment environment, Config legacy) throws IOException {
        ModelSettings settings = new ModelSettings();
        settings.crypter = FileCrypter.forEnvironment(environment);
        boolean existed = environment.getDataPath("data/model.conf").toFile().isFile();
        settings.config = environment.loadOrCreateConfig("data/model.conf", c -> {
        });

        // These three used to live in app.conf. Carry them over the first time so nothing is lost.
        if (!existed && legacy != null) {
            settings.modelFile.set(legacy.getString("modelPath", ""));
            settings.contextSize.set(legacy.getInt("contextSize", settings.contextSize.getDefault()));
            settings.responseTokens.set(legacy.getInt("responseTokens", settings.responseTokens.getDefault()));
        }
        settings.readValues(settings.config);
        settings.cloudApiKey = settings.decryptKey(settings.config.getString("cloud.apiKey", ""));
        settings.serverApiKey = settings.decryptKey(settings.config.getString("server.apiKey", ""));
        settings.huggingFaceToken = settings.decryptKey(settings.config.getString("download.huggingFaceToken", ""));
        if (!existed) settings.save();
        return settings;
    }

    public ModelSettings copy() {
        ModelSettings copy = new ModelSettings();
        copy.loadFrom(this);
        return copy;
    }

    // Takes the values from the edited copy and saves them.
    public void apply(ModelSettings edited) {
        loadFrom(edited);
        save();
    }

    public void loadFrom(ModelSettings other) {
        copyValuesFrom(other);
        cloudApiKey = other.cloudApiKey;
        serverApiKey = other.serverApiKey;
    }

    public boolean differsFrom(ModelSettings other) {
        if (valuesDifferFrom(other)) return true;
        return !cloudApiKey.equals(other.cloudApiKey) || !serverApiKey.equals(other.serverApiKey);
    }

    public void resetToDefaults() {
        resetValues();
        cloudApiKey = "";
        serverApiKey = "";
    }

    public void resetGroup(String... prefixes) {
        resetValues(prefixes);
        if (Arrays.asList(prefixes).contains("cloud.")) cloudApiKey = "";
        if (Arrays.asList(prefixes).contains("server.")) serverApiKey = "";
    }

    public String getCloudApiKey() {
        return cloudApiKey;
    }

    public void setCloudApiKey(String key) {
        cloudApiKey = key == null ? "" : key.trim();
    }

    // The key other devices need to use this computer's server. Empty means it's open.
    public String getServerApiKey() {
        return serverApiKey;
    }

    public void setServerApiKey(String key) {
        serverApiKey = key == null ? "" : key.trim();
    }

    // Lets the Download tab into gated and private repos. Empty for none.
    public String getHuggingFaceToken() {
        return huggingFaceToken;
    }

    public void setHuggingFaceToken(String token) {
        huggingFaceToken = token == null ? "" : token.trim();
        save();
    }

    public String effectiveHost() {
        return shareOnNetwork.get() ? "0.0.0.0" : host.get().trim();
    }

    public boolean usesCloud() {
        return cloudEnabled.get() && !cloudUrl.get().isBlank();
    }

    public void save() {
        if (config == null) return;
        writeValues(config);
        config.set("cloud.apiKey", encryptKey(cloudApiKey));
        config.set("server.apiKey", encryptKey(serverApiKey));
        config.set("download.huggingFaceToken", encryptKey(huggingFaceToken));
        try {
            config.save();
        } catch (IOException e) {
            App.logger.error("Could not save model.conf!", e);
        }
    }

    // API keys are encrypted with the install's secret.key so they aren't sitting in model.conf as plain text.
    private String encryptKey(String plain) {
        if (plain.isEmpty() || crypter == null) return "";
        return Base64.getEncoder().encodeToString(crypter.encrypt(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private String decryptKey(String stored) {
        if (stored == null || stored.isBlank()) return "";
        try {
            return new String(crypter.decrypt(Base64.getDecoder().decode(stored)), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            App.logger.warn("Could not read the saved API key. It will need to be entered again.", e);
            return "";
        }
    }
}
