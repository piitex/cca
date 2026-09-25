package me.piitex.cca.ui.settings;

import me.piitex.cca.background.ImageBackground;
import me.piitex.cca.config.ImageOptions;
import me.piitex.cca.ui.Components;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.ui.overlays.ButtonOverlay;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Supplier;

import static me.piitex.cca.ui.settings.SettingsUi.CONTROL_HEIGHT;

// The rows for picking a picture and laying it out, shared by the Background and Chat tabs.
// The last card, Look, is left open so the caller can add its own rows to it.
final class ImageSection {
    private final ImageOptions options;
    private final String area;
    private final Supplier<ImageBackground> shown;
    private final Runnable edited;
    private final Runnable restructured;

    // area is what the picture sits behind, like "window". shown is the picture the preview loaded, for its size and errors.
    ImageSection(ImageOptions options, String area, Supplier<ImageBackground> shown, Runnable edited, Runnable restructured) {
        this.options = options;
        this.area = area;
        this.shown = shown;
        this.edited = edited;
        this.restructured = restructured;
    }

    void rows(SettingsForm f, String title) {
        picture(f, title);
        layout(f, "Size and position");
    }

    private void picture(SettingsForm f, String title) {
        boolean chosen = !options.path().get().isBlank();
        f.section(title);
        ButtonOverlay choose = Components.accentButton(chosen ? "Change image" : "Choose image", 14f, 150, CONTROL_HEIGHT);
        choose.onAction(this::pick);
        f.row("Image file", describe(), choose);
        if (chosen) {
            ButtonOverlay remove = Components.secondaryButton("Remove", 13f, 150, CONTROL_HEIGHT);
            remove.onAction(() -> {
                options.path().set("");
                restructured.run();
            });
            f.row("Remove the picture", "Nothing is shown behind the " + area + " until you choose another.", remove);
        }
    }

    // Everything but choosing the file, for pictures that come from elsewhere.
    void layout(SettingsForm f, String title) {
        f.section(title);
        ImageBackground.Fit fit = ImageBackground.Fit.of(options.fit().get());
        f.row("Fit", fit.blurb(), SettingsUi.choice(options.fit(),
                Arrays.stream(ImageBackground.Fit.values()).map(ImageBackground.Fit::id).toList(),
                id -> ImageBackground.Fit.of(id).label(), restructured));
        f.row("Scale", "Magnify or shrink the picture on top of the fit. 100% is as fitted.", SettingsUi.decSpinner(options.zoom(), 10, 400, 5, 0, edited));
        f.row("Horizontal position", "Which part lines up with the " + area + ": 0% is the left edge, 100% the right.", SettingsUi.decSpinner(options.alignX(), 0, 100, 5, 0, edited));
        f.row("Vertical position", "0% is the top edge, 100% the bottom.", SettingsUi.decSpinner(options.alignY(), 0, 100, 5, 0, edited));

        f.section("Look");
        f.row("Opacity", "Fade the picture toward what is behind it.", SettingsUi.decSpinner(options.opacity(), 0, 1, 0.05, 2, edited));
        f.row("Overlay color", "A color laid over the picture. A dark, partly see-through one dims it so text stays readable.", SettingsUi.color(options.overlay(), true, edited));
    }

    // The picture's name and size, or why it can't be shown.
    private String describe() {
        String path = options.path().get();
        if (path.isBlank()) return "No picture chosen yet. PNG, JPEG, WebP, GIF and BMP files work.";
        String name = Path.of(path).getFileName().toString();
        ImageBackground image = shown.get();
        if (image == null) return name;
        if (image.getError() != null) return name + " — " + image.getError();
        return name + " · " + image.getImageWidth() + " × " + image.getImageHeight() + " pixels";
    }

    private void pick() {
        FileDialog.open()
                .filter("Images", "png", "jpg", "jpeg", "webp", "gif", "bmp")
                .onSelect(picked -> {
                    options.path().set(picked.toAbsolutePath().toString());
                    restructured.run();
                })
                .show();
    }
}
