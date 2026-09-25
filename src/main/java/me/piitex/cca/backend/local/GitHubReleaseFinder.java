package me.piitex.cca.backend.local;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

// Uses the public GitHub API without a token. That's limited to about 60 requests an hour which is plenty for setup.
public class GitHubReleaseFinder {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final String releasesUrl;

    public GitHubReleaseFinder(String owner, String repo) {
        this.releasesUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/releases";
    }

    // size is -1 if GitHub didn't report it
    public record ReleaseAsset(String name, String downloadUrl, long size) {
    }

    /**
     * Goes through the releases newest first and returns the first asset matching the regex.
     * Only looks at releases tagged with "b" since that's how llama.cpp tags its builds.
     *
     * @return the asset, or null if nothing matched
     */
    public ReleaseAsset findLatestAsset(String assetNameRegex) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(releasesUrl))
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API returned HTTP " + response.statusCode() + " for " + releasesUrl);
        }

        JSONArray releases = new JSONArray(response.body());
        Pattern pattern = Pattern.compile(assetNameRegex);

        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            String tag = release.optString("tag_name", "");
            JSONArray assets = release.optJSONArray("assets");
            if (!tag.startsWith("b") || assets == null || assets.isEmpty()) continue;

            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String name = asset.optString("name", "");
                if (pattern.matcher(name).matches()) {
                    return new ReleaseAsset(name, asset.getString("browser_download_url"), asset.optLong("size", -1));
                }
            }
        }
        return null;
    }
}
