package me.piitex.cca.ui.chat;

import me.piitex.cca.config.AppearanceSettings;
import me.piitex.cca.model.ChatMessage;
import me.piitex.cca.model.Role;
import me.piitex.cca.theme.Palette;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.theme.Theme;

// Avatar and a bubble that fits the text. The character is on the left in a field colored bubble,
// the user is mirrored on the right with a light accent tint.
final class TextChatStyle implements ChatStyle {
    private final Theme theme = Components.theme();

    private static final double AVATAR_GAP = 10;
    private static final double BUBBLE_PAD_X = 14;
    private static final double BUBBLE_PAD_Y = 10;
    private static final double MAX_BUBBLE_WIDTH = 560;
    private static final float MESSAGE_FONT = 14f;
    private static final double ACTIONS_TOP_GAP = 2;

    private final MessageBody.Look look = new MessageBody.Look(MESSAGE_FONT, theme.getText(), 3f, null);
    private final AvatarLook avatars;

    TextChatStyle(AppearanceSettings settings) {
        this.avatars = AvatarLook.of(settings);
    }

    @Override
    public Element buildMessage(ChatMessage message, Speaker speaker, Speaker user, double width, MessageActions actions) {
        boolean fromUser = message.getSender() == Role.USER;
        boolean editing = actions.editing() != null;

        // Snug bubble while reading, full width while editing so it doesn't resize as they type.
        double maxTextWidth = Math.max(40, Math.min(MAX_BUBBLE_WIDTH, width * 0.72) - 2 * BUBBLE_PAD_X);
        double textWidth = editing ? maxTextWidth : Math.min(maxTextWidth, measureLongestLine(message.getContent()));
        MessageBody text = editing ? actions.editing().body() : new MessageBody(look, message.getContent(), false);
        if (!editing && actions.edit() != null) text.onDoubleClick(actions.edit());
        text.fit(textWidth);

        double bubbleWidth = textWidth + 2 * BUBBLE_PAD_X;
        double bubbleHeight = text.getHeight() + 2 * BUBBLE_PAD_Y;
        Container bar = MessageActionBar.build(actions);
        double actionsHeight = bar == null ? 0 : ACTIONS_TOP_GAP + MessageActionBar.HEIGHT;
        boolean hasAvatar = avatars.shownFor(fromUser);
        double avatarWidth = avatars.width();
        double avatarHeight = avatars.height();
        double avatarSpace = hasAvatar ? avatarWidth + AVATAR_GAP : 0;
        double rowHeight = Math.max(hasAvatar ? avatarHeight : 0, bubbleHeight + actionsHeight);

        Container row = Components.transparent(width, rowHeight);

        if (hasAvatar) {
            Element avatar = fromUser ? ChatAvatars.user(user, avatarWidth, avatarHeight, avatars.shape(), avatars.image()) : ChatAvatars.character(speaker, avatarWidth, avatarHeight, avatars.shape(), avatars.image());
            avatar.setPosition(fromUser ? width - avatarWidth : 0, 0);
            row.addElement(avatar);
        }

        Container bubble = new Container(bubbleWidth, bubbleHeight);
        bubble.getStyling().setCornerRadius(16f);
        if (fromUser) {
            Components.fill(bubble, Palette.tintStrong());
            bubble.getStyling().setBorderThickness(0f);
        } else {
            Components.fill(bubble, theme.getFieldBackground());
            bubble.getStyling().setBorderThickness(1f);
            bubble.getStyling().setBorderColor(theme.getContainerBorder());
        }
        if (editing) {
            bubble.getStyling().setBorderThickness(1.5f);
            bubble.getStyling().setBorderColor(Palette.accent());
        }
        text.setPosition(BUBBLE_PAD_X, BUBBLE_PAD_Y);
        bubble.addElement(text);

        double bubbleX = fromUser ? width - avatarSpace - bubbleWidth : avatarSpace;
        bubble.setPosition(bubbleX, 0);
        row.addElement(bubble);

        if (bar != null) {
            // Lined up with the bubble's edge, nudged so the icon lines up and not its hover circle.
            bar.setPosition(fromUser ? bubbleX + bubbleWidth - bar.getWidth() + 6 : bubbleX - 6, bubbleHeight + ACTIONS_TOP_GAP);
            row.addElement(bar);
        }

        row.setContextMenu(MessageActionMenu.build(actions));
        return row;
    }

    @Override
    public MessageBody.Look look() {
        return look;
    }

    @Override
    public double messageGap() {
        return 14;
    }

    // The widest line so a short message gets a small bubble. +2 so the last word doesn't wrap from rounding.
    private double measureLongestLine(String content) {
        double widest = 0;
        for (String line : content.split("\n", -1)) {
            widest = Math.max(widest, new TextOverlay(line, MESSAGE_FONT, theme.getText()).getWidth());
        }
        return widest + 2;
    }
}
