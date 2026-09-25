package me.piitex.cca.background;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.ui.background.AbstractBackground;
import me.piitex.engine.ui.color.Color;
import org.jetbrains.skia.Image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A picture behind the app, laid out by a fit mode, a zoom and an alignment, with an optional overlay
 * color to dim it.
 * <p>
 * The file is decoded here instead of through the engine's ImageLoader because that cache is never
 * cleared, and the user can change backgrounds as often as they like.
 */
public final class ImageBackground extends AbstractBackground {

    // Tiles smaller than this are drawn at this size so a tiny image can't ask for thousands of draws a frame.
    private static final float MIN_TILE = 16f;

    public enum Fit {
        COVER("cover", "Fill", "Covers the whole window, cropping the edges if the shapes differ."),
        CONTAIN("contain", "Fit", "Shows the whole picture, with bars where the shapes differ."),
        STRETCH("stretch", "Stretch", "Stretches to the window's exact shape, distorting the picture."),
        CENTER("center", "Actual size", "The picture at its own size, without scaling."),
        TILE("tile", "Tile", "Repeats the picture at its own size across the window.");

        private final String id;
        private final String label;
        private final String blurb;

        Fit(String id, String label, String blurb) {
            this.id = id;
            this.label = label;
            this.blurb = blurb;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public String blurb() {
            return blurb;
        }

        public static Fit of(String id) {
            for (Fit fit : values()) {
                if (fit.id.equals(id)) return fit;
            }
            return COVER;
        }
    }

    private Path source;
    private Image image;
    private String error;

    private Fit fit = Fit.COVER;
    private float zoom = 1f;
    private float alignX = 0.5f;
    private float alignY = 0.5f;
    private Color overlay = Color.TRANSPARENT;
    private float cornerRadius;
    private float referenceWidth;
    private float referenceHeight;
    private boolean referenceByWidth;

    // Only decodes when the file actually changes. If it can't be read getError() says why.
    public void setSource(Path file) {
        if (file == null ? source == null : file.equals(source)) return;
        source = file;
        error = null;
        if (image != null) {
            image.close();
            image = null;
        }
        if (file == null) return;
        try {
            image = Image.Companion.makeFromEncoded(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            image = null;
        }
        if (image == null) {
            error = Files.isRegularFile(file) ? "Couldn't read that file as an image." : "That file can't be found.";
        }
    }

    public String getError() {
        return error;
    }

    public boolean hasImage() {
        return image != null;
    }

    public int getImageWidth() {
        return image == null ? 0 : image.getWidth();
    }

    public int getImageHeight() {
        return image == null ? 0 : image.getHeight();
    }

    public void setFit(Fit fit) {
        this.fit = fit;
    }

    // On top of the fit, 1 is none.
    public void setZoom(float zoom) {
        this.zoom = Math.max(0.01f, zoom);
    }

    // 0 is left/top, 0.5 centered, 1 right/bottom.
    public void setAlignment(float x, float y) {
        this.alignX = Math.max(0f, Math.min(1f, x));
        this.alignY = Math.max(0f, Math.min(1f, y));
    }

    public void setOverlay(Color overlay) {
        this.overlay = overlay == null ? Color.TRANSPARENT : overlay;
    }

    // For a panel with rounded corners.
    public void setCornerRadius(float cornerRadius) {
        this.cornerRadius = Math.max(0f, cornerRadius);
    }

    // The preview uses this to lay the picture out like a real window of this size, shrunk into the box.
    // 0 for the actual window.
    public void setReferenceSize(float width, float height) {
        setReferenceSize(width, height, false);
    }

    // With byWidth the layout is scaled to the box's width and starts at its top, so a short box shows
    // the top of a taller panel.
    public void setReferenceSize(float width, float height, boolean byWidth) {
        this.referenceWidth = Math.max(0f, width);
        this.referenceHeight = Math.max(0f, height);
        this.referenceByWidth = byWidth;
    }

    @Override
    protected void renderEffect(Renderer renderer, float x, float y, float width, float height) {
        if (image == null || width <= 0 || height <= 0) return;

        // The area the layout is done in, and how much smaller it is than the real window.
        float k = 1f;
        float ax = x, ay = y, aw = width, ah = height;
        float radius = cornerRadius;
        if (referenceWidth > 0 && referenceHeight > 0) {
            k = referenceByWidth ? width / referenceWidth : Math.min(width / referenceWidth, height / referenceHeight);
            aw = referenceWidth * k;
            ah = referenceHeight * k;
            if (!referenceByWidth) {
                ax = x + (width - aw) / 2f;
                ay = y + (height - ah) / 2f;
                radius *= k;
            }
        }

        float iw = image.getWidth();
        float ih = image.getHeight();

        renderer.pushClip(x, y, width, height, cornerRadius);
        renderer.pushClip(ax, ay, aw, ah, radius);
        switch (fit) {
            case STRETCH -> drawOne(renderer, ax, ay, aw, ah, aw * zoom, ah * zoom);
            case TILE -> tile(renderer, ax, ay, aw, ah, Math.max(MIN_TILE, iw * zoom * k), Math.max(MIN_TILE, ih * zoom * k));
            default -> {
                float scale = switch (fit) {
                    case COVER -> Math.max(aw / iw, ah / ih);
                    case CONTAIN -> Math.min(aw / iw, ah / ih);
                    default -> k; // actual size
                };
                drawOne(renderer, ax, ay, aw, ah, iw * scale * zoom, ih * scale * zoom);
            }
        }
        if (overlay.getA() > 0f) {
            renderer.drawQuad(ax, ay, aw, ah, overlay, radius);
        }
        renderer.popClip();
        renderer.popClip();
    }

    private void drawOne(Renderer renderer, float ax, float ay, float aw, float ah, float dw, float dh) {
        renderer.drawImage(image, ax + (aw - dw) * alignX, ay + (ah - dh) * alignY, dw, dh, 0f, 1f);
    }

    // The grid is lined up so one tile sits where drawOne would have put the single picture.
    private void tile(Renderer renderer, float ax, float ay, float aw, float ah, float tw, float th) {
        float firstX = ax + (aw - tw) * alignX;
        float firstY = ay + (ah - th) * alignY;
        float startX = firstX - (float) Math.ceil((firstX - ax) / tw) * tw;
        float startY = firstY - (float) Math.ceil((firstY - ay) / th) * th;
        for (float ty = startY; ty < ay + ah; ty += th) {
            for (float tx = startX; tx < ax + aw; tx += tw) {
                renderer.drawImage(image, tx, ty, tw, th, 0f, 1f);
            }
        }
    }
}
