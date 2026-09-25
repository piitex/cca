package me.piitex.cca.ui.settings;

import me.piitex.cca.App;
import me.piitex.cca.backend.model.HuggingFace;
import me.piitex.cca.backend.model.ModelDownloads;
import me.piitex.cca.backend.model.ModelLibrary;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.engine.io.download.Download;
import me.piitex.engine.io.download.DownloadState;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.download.DownloadBarContainer;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.theme.Theme;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static me.piitex.cca.ui.settings.SettingsUi.CARD_PADDING;
import static me.piitex.cca.ui.settings.SettingsUi.CONTROL_HEIGHT;

/**
 * The Download tab on the Models screen. Type words to search Hugging Face, or paste a link to a repo or a file.
 * A repo opens to its .gguf files, and a click starts the download. Downloads are shared with the whole app
 * (see ModelDownloads), so they keep going when this screen is closed and show up again when it's reopened.
 * <p>
 * The search runs on another thread. {@link #request} numbers each one so an answer for something
 * the user has already moved on from is thrown away.
 */
final class ModelDownloadTab {
    private static final double BUTTON_WIDTH = 130;
    private static final double SEARCH_WIDTH = 100;

    private final ModelDownloads downloads = ModelDownloads.get();
    private final ScrollMemory scroll;
    private final Runnable redraw;
    private boolean closed;

    private String query = "";
    private long request;
    private String loading;
    private String error;

    // The search results, null until the first one loads. resultsFor is what was searched, empty for trending.
    private List<HuggingFace.Repo> results;
    private String resultsFor = "";

    // The open repo, null while looking at the results. linkedFile is the file a pasted link pointed at.
    private String repo;
    private List<HuggingFace.Variant> variants;
    private String linkedFile;

    ModelDownloadTab(ScrollMemory scroll, Runnable redraw) {
        this.scroll = scroll;
        this.redraw = redraw;
    }

    void close() {
        closed = true;
    }

    Element build(double width, double height) {
        // The first time the tab opens there's nothing to show yet, so show what's trending.
        if (results == null && repo == null && loading == null && error == null) startSearch("");

        SettingsForm f = new SettingsForm(width, height, scroll);
        f.note("Search Hugging Face for GGUF models, or paste a link to a repo or a file.");
        f.place(searchBar(f.width), 24);
        downloadList(f);

        if (error != null) f.place(new TextFlowOverlay(error, f.width, 13f, Components.WARN), 18);
        if (loading != null) {
            f.note(loading);
        } else if (repo != null) {
            repoFiles(f);
        } else if (results != null) {
            searchResults(f);
        }
        accessToken(f);
        return f.finish();
    }

    // Search

    private Element searchBar(double width) {
        Container bar = Components.transparent(width, CONTROL_HEIGHT);
        TextFieldOverlay field = new TextFieldOverlay(width - SEARCH_WIDTH - 10, CONTROL_HEIGHT);
        field.setPlaceholder("Search models, or paste a Hugging Face link");
        field.setText(query);
        field.onTextChanged(t -> query = t);
        field.onSubmit(t -> search());
        bar.addElement(field);

        ButtonOverlay search = Components.accentButton("Search", 13f, SEARCH_WIDTH, CONTROL_HEIGHT);
        search.setPosition(width - SEARCH_WIDTH, 0);
        search.onAction(this::search);
        bar.addElement(search);
        return bar;
    }

    // A link opens straight to the repo, anything else is searched for.
    private void search() {
        HuggingFace.Link link = HuggingFace.parseLink(query);
        if (link != null) openRepo(link);
        else startSearch(query.trim());
        redraw.run();
    }

    private void startSearch(String text) {
        long mine = ++request;
        String token = token();
        loading = text.isEmpty() ? "Loading what's trending..." : "Searching for \"" + text + "\"...";
        error = null;
        repo = null;
        variants = null;
        Thread.ofVirtual().name("hf-search").start(() -> {
            List<HuggingFace.Repo> found = null;
            String failure = null;
            try {
                found = HuggingFace.search(text, token);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                App.logger.warn("Hugging Face search failed", e);
                failure = describe(e);
            }
            List<HuggingFace.Repo> result = found;
            String message = failure;
            Scheduler.runLater(() -> {
                if (closed || mine != request) return;
                loading = null;
                error = message;
                if (result != null) {
                    results = result;
                    resultsFor = text;
                }
                redraw.run();
            });
        });
    }

    private void openRepo(HuggingFace.Link link) {
        long mine = ++request;
        loading = "Looking through " + link.repo() + "...";
        error = null;
        repo = link.repo();
        variants = null;
        String token = token();
        linkedFile = link.file() != null && link.file().toLowerCase(Locale.ROOT).endsWith(".gguf") ? link.file() : null;
        Thread.ofVirtual().name("hf-repo").start(() -> {
            List<HuggingFace.Variant> found = null;
            String failure = null;
            try {
                found = HuggingFace.variants(link.repo(), link.revision(), token);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                App.logger.warn("Could not list {}", link.repo(), e);
                failure = describe(e);
            }
            List<HuggingFace.Variant> result = found;
            String message = failure;
            Scheduler.runLater(() -> {
                if (closed || mine != request) return;
                loading = null;
                error = message;
                if (result == null) {
                    repo = null; // Nothing to show for it, back to the list.
                } else {
                    variants = withLinked(result, link);
                    if (variants.isEmpty()) error = link.repo() + " has no .gguf files to download.";
                }
                redraw.run();
            });
        });
    }

    // The file a link pointed at goes first. If the repo's file list doesn't have it (a different branch, say) it's still offered.
    private List<HuggingFace.Variant> withLinked(List<HuggingFace.Variant> found, HuggingFace.Link link) {
        if (linkedFile == null) return found;
        List<HuggingFace.Variant> ordered = new ArrayList<>(found);
        HuggingFace.Variant match = ordered.stream()
                .filter(v -> v.files().stream().anyMatch(file -> file.path().equals(linkedFile)))
                .findFirst().orElse(null);
        if (match == null) {
            HuggingFace.RemoteFile file = new HuggingFace.RemoteFile(link.repo(), link.revision(), linkedFile, -1, null);
            String name = file.fileName();
            match = new HuggingFace.Variant(name.substring(0, name.length() - ".gguf".length()), "", List.of(file), -1);
        } else {
            ordered.remove(match);
        }
        ordered.addFirst(match);
        return ordered;
    }

    // Results

    private void searchResults(SettingsForm f) {
        if (results.isEmpty()) {
            f.note("Nothing matched \"" + resultsFor + "\". Try fewer words, or the model's name.");
            return;
        }
        f.heading(resultsFor.isEmpty() ? "Trending" : "Results for \"" + resultsFor + "\"");
        for (HuggingFace.Repo result : results) {
            f.place(repoRow(f.width, result), 8);
        }
    }

    private Element repoRow(double width, HuggingFace.Repo result) {
        Theme theme = Components.theme();
        double height = 62;
        Container row = new Container(width, height);
        row.getStyling().setCornerRadius(14f);
        row.getStyling().setBackgroundColor(SettingsUi.cardColor());
        row.getStyling().setHoverColor(Palette.rowHover());
        row.getStyling().setBorderThickness(1f);
        row.getStyling().setBorderColor(theme.getContainerBorder());
        Runnable open = () -> {
            openRepo(new HuggingFace.Link(result.id(), "main", null));
            redraw.run();
        };
        SettingsUi.clickable(row, open);

        double chevron = 16;
        IconOverlay arrow = new IconOverlay(Feather.CHEVRON_RIGHT, chevron, chevron);
        arrow.setTint(theme.getPlaceholderText());
        arrow.setPosition(width - CARD_PADDING - chevron, (height - chevron) / 2.0);
        SettingsUi.clickable(arrow, open);
        row.addElement(arrow);

        String meta = count(result.downloads()) + " downloads, " + count(result.likes()) + " likes"
                + (result.updated().isEmpty() ? "" : ", updated " + result.updated())
                + (result.gated() ? ", needs a login" : "");
        double textWidth = width - 2 * CARD_PADDING - chevron - 12;
        TextOverlay name = new TextOverlay(fit(result.id(), textWidth, 15f), 15f, theme.getText());
        TextOverlay detail = new TextOverlay(fit(meta, textWidth, 12f), 12f, result.gated() ? Components.WARN : theme.getPlaceholderText());
        double block = name.getHeight() + 3 + detail.getHeight();
        name.setPosition(CARD_PADDING, (height - block) / 2.0);
        detail.setPosition(CARD_PADDING, (height - block) / 2.0 + name.getHeight() + 3);
        for (TextOverlay text : new TextOverlay[]{name, detail}) {
            SettingsUi.clickable(text, open);
            row.addElement(text);
        }
        return row;
    }

    // One repo's files

    private void repoFiles(SettingsForm f) {
        ButtonOverlay back = Components.textButton("Back to results", 130, 30);
        back.onAction(() -> {
            request++;
            repo = null;
            variants = null;
            error = null;
            if (results == null) startSearch("");
            redraw.run();
        });
        f.place(back, 10);

        if (variants == null || variants.isEmpty()) return;
        f.section(repo);
        for (HuggingFace.Variant variant : variants) {
            f.row(variantTitle(variant, f.width), variantDetail(variant), variantControl(variant));
        }
        f.endCard();
    }

    private String variantTitle(HuggingFace.Variant variant, double width) {
        return fit(variant.title(), width - 2 * CARD_PADDING - BUTTON_WIDTH - 24, 14f);
    }

    private String variantDetail(HuggingFace.Variant variant) {
        List<String> parts = new ArrayList<>();
        boolean linked = variant.files().stream().anyMatch(file -> file.path().equals(linkedFile));
        if (linked) parts.add("From your link");
        if (!variant.quant().isEmpty()) parts.add(variant.quant());
        parts.add(variant.bytes() > 0 ? ModelLibrary.formatSize(variant.bytes()) : "Size shown once it starts");
        return String.join(", ", parts);
    }

    private Element variantControl(HuggingFace.Variant variant) {
        if (downloads.isDownloaded(variant)) {
            TextOverlay done = new TextOverlay("Downloaded", 13f, Components.VALID);
            Container box = Components.transparent(BUTTON_WIDTH, CONTROL_HEIGHT);
            done.setPosition(BUTTON_WIDTH - done.getWidth(), (CONTROL_HEIGHT - done.getHeight()) / 2.0);
            box.addElement(done);
            return box;
        }
        boolean busy = downloads.isDownloading(variant);
        ButtonOverlay button = busy
                ? Components.secondaryButton("Downloading", 13f, BUTTON_WIDTH, CONTROL_HEIGHT)
                : Components.accentButton("Download", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
        button.setEnabled(!busy);
        button.onAction(() -> {
            String problem = downloads.start(variant, token());
            error = problem;
            redraw.run();
        });
        return button;
    }

    // For gated and private repos. Saved as it's typed, there's no Save button on this tab.
    private void accessToken(SettingsForm f) {
        TextFieldOverlay field = new TextFieldOverlay(SettingsUi.TEXT_WIDTH, CONTROL_HEIGHT);
        field.setPlaceholder("hf_...");
        field.setPassword(true);
        field.setText(token());
        field.onTextChanged(t -> App.instance.getModelSettings().setHuggingFaceToken(t));
        f.section("Hugging Face account");
        f.row("Access token", "Optional. Needed for gated and private repos, the ones that ask you to accept terms first. Make a read token at huggingface.co/settings/tokens. "
                + "Stored encrypted on this computer and used only for huggingface.co.", field);
        f.endCard();
    }

    private static String token() {
        return App.instance.getModelSettings().getHuggingFaceToken();
    }

    // Everything downloading or finished, newest first

    private void downloadList(SettingsForm f) {
        List<Download> all = new ArrayList<>(downloads.manager().getDownloads());
        if (all.isEmpty()) return;
        Collections.reverse(all);

        double barWidth = f.width - 2 * CARD_PADDING;
        f.section("Downloads");
        for (Download download : all) {
            DownloadBarContainer bar = new DownloadBarContainer(download, barWidth);
            bar.setManager(downloads.manager());
            f.custom(bar);
            if (download.getState() == DownloadState.FAILED && download.getError() != null) {
                f.custom(new TextFlowOverlay(failure(download.getError()), barWidth, 12f, Components.WARN));
            }
        }
        if (all.stream().anyMatch(d -> d.getState().isFinished())) {
            ButtonOverlay clear = Components.secondaryButton("Clear finished", 13f, BUTTON_WIDTH, CONTROL_HEIGHT);
            clear.onAction(() -> {
                downloads.manager().clearFinished();
                redraw.run();
            });
            f.row("Finished downloads", "Take the completed, failed and cancelled ones off this list. The files stay.", clear);
        }
        f.endCard();
    }

    private static String failure(Throwable error) {
        String message = String.valueOf(error.getMessage());
        if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
            return "Hugging Face wouldn't allow this download. The repo is private or gated: accept its terms on huggingface.co, then add an access token at the bottom of this tab and press Download again.";
        }
        if (message.startsWith("Checksum mismatch")) return "The file didn't match its checksum, so it was thrown away. Retry to download it again.";
        return message;
    }

    private static String describe(Exception e) {
        if (e instanceof HuggingFace.HuggingFaceException) return e.getMessage();
        if (e instanceof IOException) return "Couldn't reach Hugging Face. Check your connection and try again.";
        return "Hugging Face sent something unexpected. Try again in a moment.";
    }

    // Cuts the text short with ... so it fits in the width.
    private static String fit(String text, double width, float size) {
        if (measure(text, size) <= width) return text;
        int end = text.length();
        while (end > 8 && measure(text.substring(0, end) + "...", size) > width) end--;
        return text.substring(0, end) + "...";
    }

    private static double measure(String text, float size) {
        return new TextOverlay(text, size, Components.theme().getText()).getWidth();
    }

    private static String count(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
