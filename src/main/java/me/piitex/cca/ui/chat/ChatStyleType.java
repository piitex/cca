package me.piitex.cca.ui.chat;

import me.piitex.cca.config.AppearanceSettings;

import java.util.function.Function;

// The chat styles, by the id saved as chat.style. Adding a style is a new ChatStyle and a line here.
public enum ChatStyleType {
    TEXT("text", "Text", "Bubbles like a messaging app. The character is on the left, you are on the right.", TextChatStyle::new),
    ROLEPLAY("roleplay", "Roleplay", "Full width cards with a name on each message, for longer replies.", RoleplayChatStyle::new);

    private final String id;
    private final String displayName;
    private final String blurb;
    private final Function<AppearanceSettings, ChatStyle> factory;

    ChatStyleType(String id, String displayName, String blurb, Function<AppearanceSettings, ChatStyle> factory) {
        this.id = id;
        this.displayName = displayName;
        this.blurb = blurb;
        this.factory = factory;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBlurb() {
        return blurb;
    }

    ChatStyle create(AppearanceSettings settings) {
        return factory.apply(settings);
    }

    // Anything unknown falls back to text so a typo in the config doesn't break chat.
    public static ChatStyleType fromId(String id) {
        for (ChatStyleType type : values()) {
            if (type.id.equalsIgnoreCase(id)) return type;
        }
        return TEXT;
    }
}
