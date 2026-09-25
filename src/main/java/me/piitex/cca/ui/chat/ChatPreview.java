package me.piitex.cca.ui.chat;

import me.piitex.cca.App;
import me.piitex.cca.background.Backgrounds;
import me.piitex.cca.background.ImageBackground;
import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.model.ChatMessage;
import me.piitex.cca.model.Role;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.scroll.ScrollContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// The chat window for Settings > Chat: the real style, at its real size, over the real window picture with a made
// up conversation that scrolls, in a column beside the options. With a picture chosen, a small box on top shows the whole
// window so the picture's scale and position can be judged. show() rebuilds the messages on every edit.
public final class ChatPreview {
    // The width of the column, the height is whatever room the tab has.
    public static final double WIDTH = 340;

    private static final double PADDING = 16;
    private static final String EXAMPLE = "data/images/avatar_example.jpeg";
    private static final Speaker SPEAKER = new Speaker("preview", "Aria", exampleAvatar());

    private static final double THUMB_GAP = 14;

    private final Container root;
    private final Container thumb;
    private final ScrollContainer list;
    private final double height;
    private final double thumbHeight;
    private boolean thumbShown;
    private final ImageBackground image = new ImageBackground();
    private final ImageBackground thumbImage = new ImageBackground();

    // panelWidth and panelHeight are the size of the real chat window, so the picture is laid out like it will be.
    public ChatPreview(double height, double panelWidth, double panelHeight) {
        this.height = height;
        root = Components.panel(WIDTH, height);
        root.getStyling().setBorderThickness(1f);
        root.getStyling().setBorderColor(Components.theme().getContainerBorder());
        image.setCornerRadius(Components.PANEL_RADIUS);
        image.setReferenceSize((float) panelWidth, (float) panelHeight, true);
        root.setBackground(image);

        // Never more than a third of the column so the conversation keeps most of it.
        double thumbWidth = WIDTH - 2 * PADDING;
        double fitHeight = thumbWidth * panelHeight / Math.max(1, panelWidth);
        double maxThumbHeight = Math.max(0, height / 3);
        if (fitHeight > maxThumbHeight) {
            fitHeight = maxThumbHeight;
            thumbWidth = fitHeight * panelWidth / Math.max(1, panelHeight);
        }
        thumbHeight = fitHeight;
        thumb = Components.panel(thumbWidth, thumbHeight);
        thumb.getStyling().setCornerRadius(10f);
        thumb.getStyling().setBorderThickness(1f);
        thumb.getStyling().setBorderColor(Components.theme().getContainerBorder());
        thumbImage.setCornerRadius(10f);
        thumb.setBackground(thumbImage);
        thumb.setPosition((WIDTH - thumbWidth) / 2.0, PADDING);

        list = new ScrollContainer(WIDTH - 2 * PADDING, Math.max(0, height - 2 * PADDING));
        list.setPosition(PADDING, PADDING);
        root.addElement(list);
    }

    // The small box only means something with a picture, so without one the conversation takes its room.
    private void showThumb(boolean show) {
        if (show == thumbShown) return;
        thumbShown = show;
        double listY = show ? PADDING + thumbHeight + THUMB_GAP : PADDING;
        if (show) root.addElement(thumb);
        else root.removeElement(thumb);
        list.setPosition(PADDING, listY);
        list.setHeight(Math.max(0, height - listY - PADDING));
    }

    public Element element() {
        return root;
    }

    // Has the picture's size and error for the settings screen.
    public ImageBackground image() {
        return image;
    }

    public void show(AppearanceSettings settings) {
        Backgrounds.configure(image, settings.chatImage);
        Backgrounds.configure(thumbImage, settings.chatImage);
        showThumb(thumbImage.hasImage());
        double offset = list.getScrollOffsetY();
        Components.clear(list);

        ChatStyleType type = ChatStyleType.fromId(settings.chatStyle.get());
        ChatStyle style = type.create(settings);
        List<ChatMessage> messages = type == ChatStyleType.ROLEPLAY
                ? List.of(new ChatMessage(Role.ASSISTANT, "Aria looks up. *She smiles.* \"You made it!\""),
                new ChatMessage(Role.USER, "Sorry, I got a little lost. *I sit down.*"))
                : List.of(new ChatMessage(Role.ASSISTANT, "You made it! I was starting to worry."),
                new ChatMessage(Role.USER, "Sorry, I got a little lost."));

        double rowWidth = Math.max(0, list.getWidth() - list.getScrollBarStyle().getThickness() - 2 * list.getScrollBarStyle().getMargin());
        double y = 0;
        for (ChatMessage message : messages) {
            Element row = style.buildMessage(message, SPEAKER, null, rowWidth, MessageActions.NONE);
            row.setPosition(0, y);
            list.addElement(row);
            y += row.getHeight() + style.messageGap();
        }
        list.scrollTo(0, Math.min(offset, list.getMaxScrollY()));
    }

    // The example picture ships inside the jar. It's copied out on first use since the engine reads files.
    private static String exampleAvatar() {
        Path file = App.environment.getDataPath(EXAMPLE);
        if (!Files.isRegularFile(file)) {
            try (InputStream in = ChatPreview.class.getResourceAsStream("/images/avatar_example.jpeg")) {
                if (in == null) return "";
                Files.createDirectories(file.getParent());
                Files.copy(in, file);
            } catch (IOException e) {
                App.logger.warn("Could not copy the example avatar", e);
                return "";
            }
        }
        return file.toString();
    }
}
