package me.piitex.cca.backend.local;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    // published is an ISO date, or empty. The build is the number in the tag (b11175), 0 if there isn't one.
    public record Release(String tag, String published, List<ReleaseAsset> assets) {
        public int build() {
            return parseBuild(tag);
        }
    }

    // 11175 from b11175, 0 for anything else.
    public static int parseBuild(String tag) {
        if (tag == null || !tag.startsWith("b")) return 0;
        try {
            return Integer.parseInt(tag.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // The newest builds with files attached, newest first. A build that was just tagged has no files until its CI is done.
    public List<Release> recent(int count) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(releasesUrl + "?per_page=" + count))
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API returned HTTP " + response.statusCode() + " for " + releasesUrl);
        }

        JSONArray releases = new JSONArray(response.body());
        List<Release> found = new ArrayList<>();
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            String tag = release.optString("tag_name", "");
            JSONArray assets = release.optJSONArray("assets");
            if (!tag.startsWith("b") || assets == null || assets.isEmpty()) continue;

            List<ReleaseAsset> files = new ArrayList<>();
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                files.add(new ReleaseAsset(asset.optString("name", ""), asset.optString("browser_download_url", ""), asset.optLong("size", -1)));
            }
            String published = release.optString("published_at", "");
            found.add(new Release(tag, published.length() >= 10 ? published.substring(0, 10) : "", files));
        }
        return found;
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
