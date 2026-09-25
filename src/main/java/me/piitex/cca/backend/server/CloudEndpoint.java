package me.piitex.cca.backend.server;

import me.piitex.cca.config.ModelSettings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// The user's own OpenAI style endpoint (Models > Cloud), like a llama-server running on another computer.
public final class CloudEndpoint {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private CloudEndpoint() {
    }

    // Works whether they typed the bare host, a url ending in /v1 or the full path.
    public static String chatUrl(String configured) {
        return baseUrl(configured) + "/v1/chat/completions";
    }

    public static String modelsUrl(String configured) {
        return baseUrl(configured) + "/v1/models";
    }

    private static String baseUrl(String configured) {
        String url = configured.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/chat/completions")) url = url.substring(0, url.length() - "/chat/completions".length());
        if (url.endsWith("/v1")) url = url.substring(0, url.length() - "/v1".length());
        return url;
    }

    // models can be empty even when ok is true.
    public record TestResult(boolean ok, String message, List<String> models) {
    }

    // Asks the endpoint for its model list. Blocks, so don't call it on the render thread.
    public static TestResult test(ModelSettings settings) {
        String configured = settings.cloudUrl.get();
        if (configured.isBlank()) return new TestResult(false, "Enter an endpoint URL first.", List.of());

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(modelsUrl(configured)))
                    .timeout(Duration.ofSeconds(10))
                    .GET();
            if (!settings.getCloudApiKey().isEmpty()) {
                builder.header("Authorization", "Bearer " + settings.getCloudApiKey());
            }
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                return new TestResult(false, "The endpoint refused the API key (HTTP " + status + ").", List.of());
            }
            if (status != 200) {
                return new TestResult(false, "The endpoint answered HTTP " + status + ".", List.of());
            }
            List<String> models = modelIds(response.body());
            String detail = models.isEmpty() ? "" : " Models: " + String.join(", ", models.subList(0, Math.min(3, models.size())))
                    + (models.size() > 3 ? " and " + (models.size() - 3) + " more." : ".");
            return new TestResult(true, "Connected." + detail, models);
        } catch (IllegalArgumentException e) {
            return new TestResult(false, "That isn't a valid URL.", List.of());
        } catch (IOException e) {
            return new TestResult(false, "Couldn't reach the endpoint: " + e.getMessage(), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestResult(false, "Interrupted.", List.of());
        }
    }

    private static List<String> modelIds(String body) {
        List<String> ids = new ArrayList<>();
        try {
            JSONObject json = new JSONObject(body);
            JSONArray data = json.optJSONArray("data");
            if (data == null) data = json.optJSONArray("models");
            if (data == null) return ids;
            for (int i = 0; i < data.length(); i++) {
                JSONObject entry = data.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("id", entry.optString("model", entry.optString("name", "")));
                if (!id.isEmpty()) ids.add(id);
            }
        } catch (JSONException ignored) {
            // It answered, so it still counts as connected even if the body is unexpected.
        }
        return ids;
    }
}
