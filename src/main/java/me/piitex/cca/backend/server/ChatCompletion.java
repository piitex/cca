package me.piitex.cca.backend.server;

import me.piitex.cca.App;
import me.piitex.cca.config.ModelSettings;
import me.piitex.cca.config.Settings.Setting;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One streamed reply from /v1/chat/completions, either the local llama-server or the cloud endpoint.
 * <p>
 * start() returns right away, the request runs on its own thread and waits for the server to be ready
 * first. cancel() stops it mid stream and whatever came in is still handed to onComplete.
 * Sampling settings are sent with every request so changing them never needs a restart.
 */
public final class ChatCompletion {

    // Called from the request thread, NOT the render thread. Use Scheduler.runLater for anything UI.
    // Either onComplete or onError is called last, never both.
    public interface Listener {
        // The reply so far, already split into the text and the reasoning. Called per chunk.
        void onProgress(String content, String reasoning);

        // content can be empty if the model produced nothing or it was cancelled early.
        void onComplete(String content, String reasoning, boolean cancelled);

        void onError(String message);
    }

    private static final Pattern THINK_BLOCK = Pattern.compile("<think>(.*?)(?:</think>|$)", Pattern.DOTALL);

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final ModelSettings settings = App.instance.getModelSettings();
    private final JSONArray messages;
    private final String userName;
    private final int maxTokens;
    private final Listener listener;

    private volatile boolean cancelled;
    private volatile InputStream body;

    private ChatCompletion(JSONArray messages, String userName, int maxTokens, Listener listener) {
        this.messages = messages;
        this.userName = userName;
        this.maxTokens = maxTokens;
        this.listener = listener;
    }

    // messages comes from PromptBuilder.build, userName is what the reply must not continue as
    public static ChatCompletion start(JSONArray messages, String userName, int maxTokens, Listener listener) {
        ChatCompletion completion = new ChatCompletion(messages, userName, maxTokens, listener);
        Thread.ofVirtual().name("chat-completion").start(completion::run);
        return completion;
    }

    // Closing the stream unblocks the read right away instead of waiting for the next chunk.
    public void cancel() {
        cancelled = true;
        InputStream stream = body;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Already closed, the request thread will notice either way.
            }
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    private void run() {
        StringBuilder raw = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        try {
            if (!settings.usesCloud() && !ServerProcess.get().awaitReady()) {
                listener.onError("The model couldn't start: " + ServerProcess.get().getErrorMessage());
                return;
            }
            if (cancelled) {
                listener.onComplete("", "", true);
                return;
            }
            stream(raw, reasoning);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
        } catch (IOException e) {
            if (!cancelled) {
                App.logger.error("Chat completion failed!", e);
                listener.onError("Lost connection to the model: " + e.getMessage());
                return;
            }
        } catch (ReplyError e) {
            listener.onError(e.getMessage());
            return;
        }

        String[] split = splitReasoning(raw.toString(), reasoning.toString());
        listener.onComplete(cleanUp(split[0]), split[1].trim(), cancelled);
    }

    private void stream(StringBuilder raw, StringBuilder reasoning) throws IOException, InterruptedException, ReplyError {
        boolean cloud = settings.usesCloud();
        String url = cloud ? CloudEndpoint.chatUrl(settings.cloudUrl.get()) : ServerProcess.get().getBaseUrl() + "/v1/chat/completions";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequest().toString()));
        if (cloud) {
            builder.timeout(Duration.ofSeconds(Math.max(5, settings.cloudTimeoutSeconds.get())));
            if (!settings.getCloudApiKey().isEmpty()) {
                builder.header("Authorization", "Bearer " + settings.getCloudApiKey());
            }
        } else if (!settings.getServerApiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + settings.getServerApiKey());
        }

        HttpResponse<InputStream> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        body = response.body();
        if (cancelled) {
            cancel(); // Cancelled while the request was being sent, close the stream we just opened.
            return;
        }

        if (response.statusCode() != 200) {
            String error;
            try (InputStream in = response.body()) {
                error = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String who = cloud ? "The cloud endpoint" : "The model";
            App.logger.error("{} rejected the request. HTTP {}: {}", who, response.statusCode(), error);
            String hint = cloud && (response.statusCode() == 401 || response.statusCode() == 403) ? " Check the API key in Models > Cloud." : "";
            throw new ReplyError(who + " rejected the request (HTTP " + response.statusCode() + "): " + errorMessage(error) + hint);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while (!cancelled && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (line.startsWith("error:")) {
                    throw new ReplyError("The model reported an error: " + errorMessage(line.substring(6).trim()));
                }
                if (!line.startsWith("data:")) continue;

                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) break;
                if (processChunk(data, raw, reasoning)) break;

                String[] split = splitReasoning(raw.toString(), reasoning.toString());
                listener.onProgress(cleanUp(split[0]), split[1].trim());
            }
        } catch (IOException e) {
            if (!cancelled) throw e; // A cancel closes the stream mid read, that isn't an error.
        }
    }

    private JSONObject buildRequest() {
        JSONObject request = new JSONObject();
        request.put("messages", messages);
        request.put("stream", true);
        request.put("max_tokens", maxTokens);
        if (settings.usesCloud() && !settings.cloudModel.get().isBlank()) {
            request.put("model", settings.cloudModel.get().trim());
        }

        request.put("temperature", settings.temperature.get());
        request.put("top_p", settings.topP.get());
        request.put("min_p", settings.minP.get());
        request.put("top_k", settings.topK.get());
        request.put("repeat_penalty", settings.repeatPenalty.get());
        request.put("repeat_last_n", settings.repeatLastN.get());
        request.put("presence_penalty", settings.presencePenalty.get());
        request.put("frequency_penalty", settings.frequencyPenalty.get());

        // The less common samplers are only sent when changed, so a plain request stays small.
        putIfChanged(request, "typical_p", settings.typicalP);
        putIfChanged(request, "top_n_sigma", settings.topNSigma);
        putIfChanged(request, "dry_multiplier", settings.dryMultiplier);
        if (settings.dryMultiplier.get() > 0) {
            request.put("dry_base", settings.dryBase.get());
            request.put("dry_allowed_length", settings.dryAllowedLength.get());
            request.put("dry_penalty_last_n", settings.dryPenaltyLastN.get());
        }
        putIfChanged(request, "xtc_probability", settings.xtcProbability);
        if (settings.xtcProbability.get() > 0) request.put("xtc_threshold", settings.xtcThreshold.get());
        putIfChanged(request, "mirostat", settings.mirostat);
        if (settings.mirostat.get() > 0) {
            request.put("mirostat_tau", settings.mirostatTau.get());
            request.put("mirostat_eta", settings.mirostatEta.get());
        }
        putIfChanged(request, "dynatemp_range", settings.dynatempRange);
        if (settings.dynatempRange.get() > 0) request.put("dynatemp_exponent", settings.dynatempExponent.get());
        putIfChanged(request, "seed", settings.seed);

        // End of turn tokens for the common templates in case the template doesn't stop on its own,
        // plus the user's name so a small model can't keep going and write the user's side.
        JSONArray stop = new JSONArray();
        stop.put("<|eot_id|>");
        stop.put("<|im_end|>");
        stop.put("<|end_of_text|>");
        stop.put("</s>");
        stop.put("\n" + userName + ":");
        request.put("stop", stop);
        return request;
    }

    private static void putIfChanged(JSONObject request, String key, Setting<? extends Number> setting) {
        if (!setting.isDefault()) request.put(key, setting.get());
    }

    // Adds the chunk's text to raw (and reasoning if the server sends it separately). Returns true once it's finished.
    private static boolean processChunk(String data, StringBuilder raw, StringBuilder reasoning) throws ReplyError {
        JSONObject chunk;
        try {
            chunk = new JSONObject(data);
        } catch (JSONException e) {
            return false; // Not json, nothing to take from it.
        }
        if (chunk.has("error")) {
            throw new ReplyError("The model reported an error: " + errorMessage(data));
        }

        JSONArray choices = chunk.optJSONArray("choices");
        if (choices == null) return false;
        boolean finished = false;
        for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.getJSONObject(i);
            JSONObject delta = choice.optJSONObject("delta");
            if (delta != null) {
                if (delta.has("content") && !delta.isNull("content")) raw.append(delta.getString("content"));
                if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                    reasoning.append(delta.getString("reasoning_content"));
                }
            }
            if (choice.has("finish_reason") && !choice.isNull("finish_reason")) finished = true;
        }
        return finished;
    }

    // Pulls <think> blocks out of the reply. An unclosed block means it's still thinking, so it all
    // counts as reasoning and half a thought never shows up as the reply.
    // Returns {content, reasoning}
    private static String[] splitReasoning(String raw, String streamedReasoning) {
        StringBuilder reasoning = new StringBuilder(streamedReasoning);
        Matcher matcher = THINK_BLOCK.matcher(raw);
        StringBuilder content = new StringBuilder();
        while (matcher.find()) {
            if (!reasoning.isEmpty()) reasoning.append('\n');
            reasoning.append(matcher.group(1).trim());
            matcher.appendReplacement(content, "");
        }
        matcher.appendTail(content);
        return new String[]{content.toString(), reasoning.toString()};
    }

    // Removes template tokens a bad template can leak into the text.
    private static String cleanUp(String text) {
        return text.replace("<|im_end|>", "")
                .replace("<im_end>", "")
                .replace("<|im_start|>assistant", "")
                .replace("<|im_start|>user", "")
                .replace("<|eot_id|>", "")
                .strip();
    }

    // Gets the message out of {"error": {"message": ...}}, or returns the body if it isn't shaped like that.
    private static String errorMessage(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            if (error != null) return error.optString("message", body);
            return json.optString("message", body);
        } catch (JSONException e) {
            return body;
        }
    }

    // An error that should be shown to the user as is.
    private static final class ReplyError extends Exception {
        ReplyError(String message) {
            super(message);
        }
    }
}
