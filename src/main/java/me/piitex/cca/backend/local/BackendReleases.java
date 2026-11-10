package me.piitex.cca.backend.local;

import me.piitex.cca.backend.local.GitHubReleaseFinder.Release;
import me.piitex.cca.backend.local.GitHubReleaseFinder.ReleaseAsset;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the latest llama.cpp releases have for each backend this computer could use.
 * Asking GitHub is limited to about 60 requests an hour without a login, so an answer is reused for a few minutes.
 */
public final class BackendReleases {
    private static final String OWNER = "ggml-org";
    private static final String REPO = "llama.cpp";
    // A build is missing files while its CI is still uploading, and a few kinds are only published now and then.
    private static final int RELEASES_LOOKED_AT = 8;
    private static final Duration REUSE_FOR = Duration.ofMinutes(5);

    // What to download for one backend. runtime is the CUDA libraries, null when the build doesn't need them.
    public record Offer(BackendVariant variant, String tag, String published, ReleaseAsset asset, ReleaseAsset runtime) {
        public int build() {
            return GitHubReleaseFinder.parseBuild(tag);
        }

        // Both downloads together, 0 if GitHub didn't say.
        public long size() {
            return Math.max(0, asset.size()) + (runtime == null ? 0 : Math.max(0, runtime.size()));
        }
    }

    // offer is null when none of the recent releases has this backend.
    public record Listing(BackendVariant variant, Offer offer) {
    }

    public record Result(List<Listing> listings, Instant checked) {
    }

    // Read without a lock, a lookup holds the fetch lock for as long as GitHub takes to answer.
    private static volatile Result cached;
    private static final Object FETCH_LOCK = new Object();

    private BackendReleases() {
    }

    // Blocks on the network, so only call it from a background thread.
    public static Result fetch(boolean force) throws IOException, InterruptedException {
        synchronized (FETCH_LOCK) {
            Result last = cached;
            if (!force && last != null && Instant.now().isBefore(last.checked().plus(REUSE_FOR))) return last;

            List<Release> releases = new GitHubReleaseFinder(OWNER, REPO).recent(RELEASES_LOOKED_AT);
            List<Listing> listings = new ArrayList<>();
            for (BackendVariant variant : BackendCatalog.forCurrentSystem()) {
                listings.add(new Listing(variant, newest(variant, releases)));
            }
            Result fresh = new Result(List.copyOf(listings), Instant.now());
            cached = fresh;
            return fresh;
        }
    }

    // Null if nothing has been fetched yet.
    public static Result cachedResult() {
        return cached;
    }

    private static Offer newest(BackendVariant variant, List<Release> releases) {
        Pattern asset = Pattern.compile(variant.assetRegex());
        Pattern runtime = variant.runtimeRegex() == null ? null : Pattern.compile(variant.runtimeRegex());
        for (Release release : releases) {
            ReleaseAsset main = first(release, asset);
            ReleaseAsset extra = runtime == null ? null : first(release, runtime);
            if (main != null && (runtime == null || extra != null)) {
                return new Offer(variant, release.tag(), release.published(), main, extra);
            }
        }
        return null;
    }

    private static ReleaseAsset first(Release release, Pattern pattern) {
        for (ReleaseAsset asset : release.assets()) {
            if (pattern.matcher(asset.name()).matches()) return asset;
        }
        return null;
    }
}
