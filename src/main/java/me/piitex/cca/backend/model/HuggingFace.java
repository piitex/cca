package me.piitex.cca.backend.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds GGUF models on huggingface.co. Search and file lists come from the public API, which needs no account
 * unless the repo is gated or private, which takes an access token. Every call blocks, so run them off the render thread.
 */
public final class HuggingFace {
    private static final String SITE = "https://huggingface.co";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // Pipelines that can't be chatted with. Anything else, including repos with no pipeline at all, is kept.
    private static final Set<String> NOT_CHAT = Set.of("text-to-image", "image-to-image", "text-to-video", "image-to-video",
            "text-to-speech", "text-to-audio", "audio-to-audio", "automatic-speech-recognition", "image-to-3d", "text-to-3d",
            "feature-extraction", "sentence-similarity", "image-classification", "object-detection");

    // 00001-of-00005 at the end of a file name means the model is split into parts.
    private static final Pattern SHARD = Pattern.compile("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANT = Pattern.compile("(?<![A-Za-z0-9])((?:IQ|Q)\\d[A-Z0-9_]*|BF16|FP16|F16|FP32|F32)(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_PAGE = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    // updated is an ISO date, or empty.
    public record Repo(String id, long downloads, long likes, boolean gated, String updated) {
    }

    // sha256 is null when Hugging Face doesn't have one (small files that aren't stored as LFS).
    public record RemoteFile(String repo, String revision, String path, long size, String sha256) {
        public String fileName() {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        public URI url() {
            return URI.create(SITE + "/" + repo + "/resolve/" + encodeSegments(revision) + "/" + encodeSegments(path));
        }
    }

    // One thing to pick from a repo: a single .gguf, or every part of a split model. bytes is the total.
    public record Variant(String title, String quant, List<RemoteFile> files, long bytes) {
    }

    // What a pasted link points at. file is null for a whole repo.
    public record Link(String repo, String revision, String file) {
    }

    // The request failed in a way worth telling the user about, message is fit to show.
    public static final class HuggingFaceException extends IOException {
        HuggingFaceException(String message) {
            super(message);
        }
    }

    private HuggingFace() {
    }

    // Blank query gives what's trending. Sorted by downloads otherwise, since that's usually the one worth having.
    public static List<Repo> search(String query, String token) throws IOException, InterruptedException {
        boolean trending = query.isBlank();
        StringBuilder url = new StringBuilder(SITE + "/api/models?filter=gguf&limit=40");
        url.append(trending ? "&sort=trendingScore" : "&sort=downloads&direction=-1&search=" + encode(query.trim()));
        for (String field : List.of("downloads", "likes", "lastModified", "gated", "pipeline_tag")) {
            url.append("&expand%5B%5D=").append(field);
        }

        JSONArray found = new JSONArray(send(url.toString(), token).body());
        List<Repo> repos = new ArrayList<>();
        for (int i = 0; i < found.length(); i++) {
            JSONObject model = found.getJSONObject(i);
            if (NOT_CHAT.contains(model.optString("pipeline_tag", ""))) continue;
            String updated = model.optString("lastModified", "");
            repos.add(new Repo(model.getString("id"), model.optLong("downloads"), model.optLong("likes"),
                    isGated(model.opt("gated")),
                    updated.length() >= 10 ? updated.substring(0, 10) : ""));
        }
        return repos;
    }

    // Every model file in the repo, smallest first. mmproj files are left out, they can't be loaded as a chat model.
    public static List<Variant> variants(String repo, String revision, String token) throws IOException, InterruptedException {
        List<RemoteFile> files = new ArrayList<>();
        String next = SITE + "/api/models/" + repo + "/tree/" + encodeSegments(revision) + "?recursive=true";
        while (next != null) {
            HttpResponse<String> response = send(next, token);
            JSONArray page = new JSONArray(response.body());
            for (int i = 0; i < page.length(); i++) {
                JSONObject entry = page.getJSONObject(i);
                String path = entry.optString("path", "");
                String lower = path.toLowerCase(Locale.ROOT);
                if (!"file".equals(entry.optString("type")) || !lower.endsWith(".gguf") || lower.contains("mmproj")) continue;
                JSONObject lfs = entry.optJSONObject("lfs");
                files.add(new RemoteFile(repo, revision, path, lfs != null ? lfs.optLong("size", entry.optLong("size")) : entry.optLong("size"),
                        lfs != null ? lfs.optString("oid", null) : null));
            }
            // Big repos come in pages.
            Matcher link = NEXT_PAGE.matcher(response.headers().firstValue("Link").orElse(""));
            next = link.find() ? link.group(1) : null;
        }
        return group(files);
    }

    // Parts of a split model are one variant, the parts are kept in the order they need to load.
    static List<Variant> group(List<RemoteFile> files) {
        Map<String, List<RemoteFile>> groups = new LinkedHashMap<>();
        for (RemoteFile file : files) {
            Matcher shard = SHARD.matcher(file.path());
            String key = shard.matches() ? shard.group(1) + "/" + shard.group(3) : file.path();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(file);
        }

        List<Variant> variants = new ArrayList<>();
        for (List<RemoteFile> parts : groups.values()) {
            parts.sort(Comparator.comparing(RemoteFile::path));
            RemoteFile first = parts.getFirst();
            Matcher shard = SHARD.matcher(first.fileName());
            boolean split = shard.matches();
            String title = split ? shard.group(1) + " (" + shard.group(3).replaceFirst("^0+", "") + " parts)"
                    : first.fileName().substring(0, first.fileName().length() - ".gguf".length());
            Matcher quant = QUANT.matcher(first.fileName());
            variants.add(new Variant(title, quant.find() ? quant.group(1).toUpperCase(Locale.ROOT) : "",
                    List.copyOf(parts), parts.stream().mapToLong(RemoteFile::size).sum()));
        }
        variants.sort(Comparator.comparingLong(Variant::bytes));
        return variants;
    }

    /**
     * Understands a repo page, a file page or download link, or just owner/name. Null if it isn't any of those,
     * which is how the search box tells a link from words to search for.
     */
    public static Link parseLink(String input) {
        String text = input.trim();
        if (text.isEmpty() || text.contains(" ")) return null;

        String path;
        Matcher host = Pattern.compile("^(?:https?://)?(?:www\\.)?(?:huggingface\\.co|hf\\.co)/(.*)$", Pattern.CASE_INSENSITIVE).matcher(text);
        if (host.matches()) {
            path = host.group(1);
        } else if (text.matches("[\\w.-]+/[\\w.-]+")) {
            path = text; // owner/name
        } else {
            return null;
        }

        int cut = path.indexOf('?');
        if (cut < 0) cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        String[] parts = path.split("/");
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null;
        // Not repos: huggingface.co/models, /spaces/..., /datasets/..., /docs/...
        if (Set.of("models", "spaces", "datasets", "docs", "blog", "papers", "collections", "settings", "organizations").contains(parts[0].toLowerCase(Locale.ROOT))) return null;

        String repo = parts[0] + "/" + parts[1];
        if (parts.length >= 5 && (parts[2].equals("resolve") || parts[2].equals("blob"))) {
            String file = decode(String.join("/", Arrays.copyOfRange(parts, 4, parts.length)));
            return new Link(repo, decode(parts[3]), file);
        }
        String revision = parts.length >= 4 && (parts[2].equals("tree") || parts[2].equals("resolve") || parts[2].equals("blob")) ? decode(parts[3]) : "main";
        return new Link(repo, revision, null);
    }

    // false, or "auto" / "manual" for a repo that wants you to accept its terms first.
    private static boolean isGated(Object gated) {
        return gated instanceof String || Boolean.TRUE.equals(gated);
    }

    // The token is optional, blank sends nothing.
    private static HttpResponse<String> send(String url, String token) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET();
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> response;
        try {
            response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // A connection left idle since the last request can be closed on us, a fresh one works.
            response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
        switch (response.statusCode()) {
            case 200 -> {
                return response;
            }
            case 401, 403 -> throw new HuggingFaceException("Hugging Face wouldn't show that repo. It may not exist, or it is private or gated and needs an access token (there is a field for one at the bottom of this tab).");
            case 404 -> throw new HuggingFaceException("Hugging Face doesn't have that repo. Check the link.");
            case 429 -> throw new HuggingFaceException("Hugging Face is limiting requests right now. Try again in a minute.");
            default -> throw new HuggingFaceException("Hugging Face answered HTTP " + response.statusCode() + ".");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    // Slashes stay, everything else that isn't safe in a path is escaped.
    private static String encodeSegments(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment -> encode(segment).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }
}
