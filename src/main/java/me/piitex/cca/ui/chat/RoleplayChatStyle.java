package me.piitex.cca.ui.chat;

import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.model.ChatMessage;
import me.piitex.cca.model.Role;
import me.piitex.cca.theme.AppTheme;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.SeparatorOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.TextSpan;
import me.piitex.engine.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Each message is a full width card with the speaker's picture and name on top, then the text at a bigger
 * size. Made for replies that are a few paragraphs long.
 * <p>
 * The character gets a big portrait and the user a small badge, so the character stays the focus.
 * "Dialogue" and *actions* are colored differently from the narration.
 */
final class RoleplayChatStyle implements ChatStyle {
    private final Theme theme = Components.theme();

    private static final double CARD_PADDING = 22;
    private static final double USER_AVATAR_SIZE = 44;
    private static final double HEADER_GAP = 18;
    private static final double SECTION_GAP = 16;
    private static final float NAME_FONT = 22f;
    private static final float BODY_FONT = 16f;
    private static final float BODY_LINE_SPACING = 7f;

    // "straight" or “curly” quotes, or *asterisks*. One pattern so the matches come out in order and never overlap.
    private static final Pattern MARKUP = Pattern.compile("\"[^\"\\n]*\"|“[^”\\n]*”|\\*[^*\\n]+\\*");

    private final AvatarLook avatars;
    private final Color dialogue;
    private final Color action;
    private final MessageBody.Look look = new MessageBody.Look(BODY_FONT, theme.getText(), BODY_LINE_SPACING, this::styleSpans);

    RoleplayChatStyle(AppearanceSettings settings) {
        avatars = AvatarLook.of(settings);
        dialogue = colorOf(settings.dialogueColor.get(), Color.LIGHT_YELLOW);
        action = colorOf(settings.actionColor.get(), Color.LIGHT_BLUE);
    }

    private static Color colorOf(String hex, Color fallback) {
        Color parsed = AppTheme.parseHex(hex);
        return parsed == null ? fallback : parsed;
    }

    @Override
    public Element buildMessage(ChatMessage message, Speaker speaker, Speaker user, double width, MessageActions actions) {
        boolean fromUser = message.getSender() == Role.USER;
        double innerWidth = Math.max(0, width - 2 * CARD_PADDING);

        Element picture = !avatars.shownFor(fromUser) ? null
                : fromUser ? ChatAvatars.user(user, USER_AVATAR_SIZE, USER_AVATAR_SIZE, avatars.shape(), avatars.image())
                : ChatAvatars.character(speaker, avatars.portraitWidth(), avatars.portraitHeight(), avatars.shape(), avatars.image());

        String name = fromUser ? ChatAvatars.userName(user) : speaker.name();
        TextOverlay nameText = new TextOverlay(name.isBlank() ? "Unnamed" : name, NAME_FONT, theme.getText());
        nameText.setBold(true);

        SeparatorOverlay divider = new SeparatorOverlay(innerWidth);
        divider.setLineColor(theme.getContainerBorder());

        boolean editing = actions.editing() != null;
        MessageBody body = editing ? actions.editing().body() : new MessageBody(look, message.getContent(), false);
        if (!editing && actions.edit() != null) body.onDoubleClick(actions.edit());
        body.fit(innerWidth);

        double pictureWidth = picture == null ? 0 : picture.getWidth() + HEADER_GAP;
        double headerHeight = picture == null ? nameText.getHeight() : picture.getHeight();
        double dividerY = CARD_PADDING + headerHeight + SECTION_GAP;
        double bodyY = dividerY + divider.getHeight() + SECTION_GAP;
        double actionsY = bodyY + body.getHeight() + SECTION_GAP - 6;
        double cardHeight = actionsY + MessageActionBar.HEIGHT + CARD_PADDING - 8;

        Container card = new Container(width, cardHeight);
        card.getStyling().setCornerRadius(18f);
        card.getStyling().setBorderThickness(editing ? 1.5f : 1f);
        card.getStyling().setBorderColor(editing ? Palette.accent() : theme.getContainerBorder());
        // The user's cards get the same tint as their text bubbles.
        Components.fill(card, fromUser ? Palette.tint() : theme.getFieldBackground());

        if (picture != null) {
            picture.setPosition(CARD_PADDING, CARD_PADDING);
            card.addElement(picture);
        }

        // Top of the portrait for the character, centered on the small badge for the user.
        double nameY = fromUser || picture == null ? CARD_PADDING + (headerHeight - nameText.getHeight()) / 2.0 : CARD_PADDING + 4;
        nameText.setPosition(CARD_PADDING + pictureWidth, nameY);
        card.addElement(nameText);

        divider.setPosition(CARD_PADDING, dividerY);
        card.addElement(divider);

        body.setPosition(CARD_PADDING, bodyY);
        card.addElement(body);

        Container bar = MessageActionBar.build(actions);
        if (bar != null) {
            bar.setPosition(CARD_PADDING - 6, actionsY); // Lines up the icons with the text, not their hover circles.
            card.addElement(bar);
        }

        card.setContextMenu(MessageActionMenu.build(actions));
        return card;
    }

    @Override
    public MessageBody.Look look() {
        return look;
    }

    @Override
    public double messageGap() {
        return 18;
    }

    // Uses the matcher's char indexes as they are. TextSpan says code points but the text area actually
    // slices lines with substring, so converting would shift every span after an emoji.
    private List<TextSpan> styleSpans(String text) {
        List<TextSpan> spans = new ArrayList<>();
        Matcher matcher = MARKUP.matcher(text);
        while (matcher.find()) {
            Color color = text.charAt(matcher.start()) == '*' ? action : dialogue;
            spans.add(new TextSpan(matcher.start(), matcher.end(), color));
        }
        return spans;
    }
}
