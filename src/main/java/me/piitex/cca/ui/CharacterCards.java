package me.piitex.cca.ui;

import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.model.HubItem;
import me.piitex.cca.theme.Palette;
import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.ImageOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// The character, user and folder cards on the hubs. Settings > Characters draws its preview with these too.
public final class CharacterCards {
    public static final Color BORDER = new Color(1f, 1f, 1f, 0.10f);

    private static final double PADDING = 10;

    // The size and shape of a card, read from the appearance settings.
    public record Look(double width, double gap, float radius, boolean buttons, boolean folderPortraits) {

        public static Look of(AppearanceSettings settings) {
            return new Look(settings.cardWidth.get(), settings.cardGap.get(), settings.cardRadius.get(),
                    settings.cardButtons.get(), settings.folderPortraits.get());
        }

        // The image is square, the name and the buttons go under it.
        public double height() {
            return width + (buttons ? 74 : 32);
        }

        double imageSize() {
            return width - 2 * PADDING;
        }

        // The corners of what's inside a card follow the card's.
        float innerRadius() {
            return Math.max(0f, radius - 4f);
        }
    }

    // What a card shows of a character or user, so Settings can draw made up ones.
    public record Face(String id, String displayName, String iconPath) {

        public static Face of(HubItem item) {
            return new Face(item.getId(), item.getDisplayName(), item.getIconPath());
        }

        String name() {
            return id.isBlank() ? "Unnamed" : id;
        }
    }

    private CharacterCards() {
    }

    private static Container base(Look look) {
        Container card = new Container(look.width(), look.height());
        card.useTheme();
        card.getStyling().setCornerRadius(look.radius());
        card.getStyling().setBorderColor(BORDER);
        card.getStyling().setHoverColor(Palette.rowHover());
        card.setCursorMode(CursorMode.POINTER);
        return card;
    }

    // The buttons under the name, centered as a group.
    private static void addControls(Container card, Look look, double y, Container... buttons) {
        double gap = 6;
        double groupWidth = buttons.length * Components.CONTROL_SIZE + (buttons.length - 1) * gap;
        double x = (look.width() - groupWidth) / 2.0;
        for (Container button : buttons) {
            button.setPosition(x, y);
            card.addElement(button);
            x += Components.CONTROL_SIZE + gap;
        }
    }

    public static Container character(Look look, Face character, Runnable edit, Runnable copy, Runnable delete) {
        // No click handlers on the card itself. The grid reports clicks on release since a press could turn into a drag.
        Container card = base(look);

        Container image = portrait(character, look.imageSize(), look.innerRadius(), 40f);
        image.setPosition(PADDING, PADDING);
        card.addElement(image);

        TextOverlay name = new TextOverlay(character.name(), 15f, Components.theme().getText());
        double nameY = PADDING + look.imageSize() + 8;
        name.setPosition((look.width() - name.getWidth()) / 2.0, nameY);
        name.setCursorMode(CursorMode.POINTER);
        card.addElement(name);

        if (look.buttons()) {
            addControls(card, look, nameY + name.getHeight() + 10,
                    Components.iconButton(Feather.EDIT, "Edit character", Components.CONTROL_EDIT, Components.CONTROL_EDIT_HOVER, edit),
                    Components.iconButton(Feather.COPY, "Duplicate character", Components.CONTROL_COPY, Components.CONTROL_COPY_HOVER, copy),
                    Components.iconButton(Feather.TRASH, "Delete character", Components.CONTROL_DANGER, Components.CONTROL_DANGER_HOVER, delete));
        }
        return card;
    }

    public static Container folder(Look look, String folderName, List<Face> members, Runnable rename, Runnable delete) {
        Container card = base(look);

        double imageSize = look.imageSize();
        Container preview = new Container(imageSize, imageSize);
        preview.getStyling().setCornerRadius(look.innerRadius());
        preview.getStyling().setBorderThickness(0f);
        Components.fill(preview, Palette.tintStrong());
        preview.setCursorMode(CursorMode.POINTER);

        if (members.isEmpty() || !look.folderPortraits()) {
            IconOverlay icon = new IconOverlay(Feather.FOLDER, 48, 48);
            icon.setTint(Palette.accent());
            icon.setPosition((imageSize - 48) / 2.0, (imageSize - 48) / 2.0);
            preview.addElement(icon);
        } else {
            // Up to four small portraits in a 2x2, the last one turns into "+N" when there are more.
            double inset = 10, tileGap = 8;
            double tile = (imageSize - 2 * inset - tileGap) / 2.0;
            float tileRadius = Math.max(0f, look.innerRadius() - 2f);
            for (int i = 0; i < Math.min(4, members.size()); i++) {
                Container mini;
                if (i == 3 && members.size() > 4) {
                    mini = new Container(tile, tile);
                    mini.getStyling().setCornerRadius(tileRadius);
                    mini.getStyling().setBorderThickness(0f);
                    Components.fill(mini, Palette.tintStrong());
                    TextOverlay more = new TextOverlay("+" + (members.size() - 3), 18f, Components.theme().getText());
                    more.setPosition((tile - more.getWidth()) / 2.0, (tile - more.getHeight()) / 2.0);
                    mini.addElement(more);
                } else {
                    mini = portrait(members.get(i), tile, tileRadius, 22f);
                }
                mini.setPosition(inset + (i % 2) * (tile + tileGap), inset + (i / 2) * (tile + tileGap));
                preview.addElement(mini);
            }
        }
        preview.setPosition(PADDING, PADDING);
        card.addElement(preview);

        TextOverlay name = Components.fitText(folderName, 15f, Components.theme().getText(), look.width() - 2 * PADDING);
        double nameY = PADDING + imageSize + 8;
        name.setPosition((look.width() - name.getWidth()) / 2.0, nameY);
        name.setCursorMode(CursorMode.POINTER);
        card.addElement(name);

        // The second button removes the folder, not the characters in it.
        if (look.buttons()) {
            addControls(card, look, nameY + name.getHeight() + 10,
                    Components.iconButton(Feather.EDIT, "Rename folder", Components.CONTROL_EDIT, Components.CONTROL_EDIT_HOVER, rename),
                    Components.iconButton(Feather.FOLDER_MINUS, "Delete folder (keeps its characters)", Components.CONTROL_DANGER, Components.CONTROL_DANGER_HOVER, delete));
        }
        return card;
    }

    // The character's icon, or their initial on their own color if they don't have one.
    private static Container portrait(Face character, double size, float radius, float initialSize) {
        Container portrait = new Container(size, size);
        portrait.getStyling().setCornerRadius(radius);
        portrait.getStyling().setBorderThickness(0f);
        portrait.setCursorMode(CursorMode.POINTER);

        Path iconPath = Path.of(character.iconPath());
        if (Files.isRegularFile(iconPath)) {
            Components.fill(portrait, Color.TRANSPARENT);
            ImageOverlay icon = new ImageOverlay(iconPath.toString(), size, size);
            icon.getStyling().setCornerRadius(radius);
            icon.setCursorMode(CursorMode.POINTER);
            portrait.addElement(icon);
        } else {
            Components.fill(portrait, Components.accentColorFor(character.id()));
            String displayName = character.displayName();
            String initial = displayName.isBlank() ? "?" : displayName.substring(0, 1).toUpperCase();
            TextOverlay initialText = new TextOverlay(initial, initialSize, Color.WHITE);
            initialText.setPosition((size - initialText.getWidth()) / 2.0, (size - initialText.getHeight()) / 2.0);
            initialText.setCursorMode(CursorMode.POINTER);
            portrait.addElement(initialText);
        }
        return portrait;
    }
}
