package me.piitex.cca.ui.chat;

import me.piitex.cca.background.Backgrounds;
import me.piitex.cca.background.ImageBackground;
import me.piitex.cca.config.ImageOptions;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.StackLayout;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

// The pictures next to messages. Shared by every style so a character looks the same everywhere, including the hub.
final class ChatAvatars {

    private static final int MAX_PICTURES = 8;
    private static final Map<Path, ImageBackground> PICTURES = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, ImageBackground> eldest) {
            return size() > MAX_PICTURES;
        }
    };

    private ChatAvatars() {
    }

    // The character's icon laid out by the fit settings, or their initial on their hub color.
    static Element character(Speaker character, double width, double height, AvatarShape shape, ImageOptions options) {
        float radius = shape.radius(width, height);
        ImageBackground picture = picture(Path.of(character.iconPath()), options, radius);
        if (picture == null) return initialBadge(character, width, height, radius);
        Container icon = Components.transparent(width, height);
        icon.setBackground(picture);
        return icon;
    }

    // One decoded picture per icon file, since a chat builds an avatar for every message.
    private static ImageBackground picture(Path file, ImageOptions options, float radius) {
        if (!Files.isRegularFile(file)) return null;
        ImageBackground picture = PICTURES.computeIfAbsent(file, key -> new ImageBackground());
        Backgrounds.configure(picture, options, file);
        picture.setCornerRadius(radius);
        return picture.hasImage() ? picture : null;
    }

    private static Element initialBadge(Speaker character, double width, double height, float cornerRadius) {
        StackLayout badge = new StackLayout(width, height);
        badge.setAlignment(Layout.Alignment.CENTER);
        Components.fill(badge, Components.accentColorFor(character.id()));
        badge.getStyling().setCornerRadius(cornerRadius);
        String name = character.name();
        String initial = name.isBlank() ? "?" : name.substring(0, 1).toUpperCase();
        badge.addElement(new TextOverlay(initial, (float) (Math.min(width, height) * 0.44), Color.WHITE));
        return badge;
    }

    // What the user's messages are signed with.
    static String userName(Speaker user) {
        return user == null || user.name().isBlank() ? "You" : user.name();
    }

    // The user template's icon or initial. Without a template the user gets the app's gradient badge.
    static Element user(Speaker user, double width, double height, AvatarShape shape, ImageOptions options) {
        if (user != null) {
            float radius = shape.radius(width, height);
            ImageBackground picture = picture(Path.of(user.iconPath()), options, radius);
            if (picture == null) return initialBadge(user, width, height, radius);
            Container icon = Components.transparent(width, height);
            icon.setBackground(picture);
            return icon;
        }
        double iconSize = Math.round(Math.min(width, height) * 0.47);
        StackLayout badge = new StackLayout(width, height);
        badge.setAlignment(Layout.Alignment.CENTER);
        badge.getStyling().setBackgroundColor(new LinearGradient(Palette.accentStart(), Palette.accentEnd()));
        badge.getStyling().setCornerRadius(shape.radius(width, height));
        IconOverlay icon = new IconOverlay(Feather.USER, iconSize, iconSize);
        icon.setTint(Color.WHITE);
        badge.addElement(icon);
        return badge;
    }
}
