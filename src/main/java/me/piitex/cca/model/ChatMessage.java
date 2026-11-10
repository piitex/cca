package me.piitex.cca.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A single message. It can hold several variants (regenerated replies for the same spot), only one is
 * active at a time. Everything outside this class only sees the active one. Saving goes through Chat.
 */
public class ChatMessage {

    public static final class Variant {
        private String content;
        private String reasoning;

        public Variant(String content, String reasoning) {
            this.content = content == null ? "" : content;
            this.reasoning = reasoning;
        }

        public String getContent() {
            return content;
        }

        public String getReasoning() {
            return reasoning;
        }
    }
    private Role sender;
    // The character the chat belongs to. Who wrote an assistant message is speakerId, this is what it falls back to.
    private Character character;
    // The id of the character that wrote a reply, so a group chat knows who said what. Empty for the user's messages
    // and for chats saved before groups existed, those were always the chat's own character.
    private String speakerId;
    private String imageUrl;
    private final List<Variant> variants = new ArrayList<>();
    private int active;

    public ChatMessage(Role sender, Character character, String content, String imageUrl, String reasoning) {
        this.sender = sender;
        this.character = character;
        this.speakerId = speakerIdFor(sender, character);
        this.imageUrl = imageUrl;
        variants.add(new Variant(content, reasoning));
    }

    public ChatMessage(Role sender, Character character, String content) {
        this(sender, character, content, null, null);
    }

    // Used when loading a chat file. variants can't be empty.
    ChatMessage(Role sender, Character character, String speakerId, String imageUrl, List<Variant> variants, int active) {
        this.sender = sender;
        this.character = character;
        this.speakerId = speakerId == null ? "" : speakerId;
        this.imageUrl = imageUrl;
        this.variants.addAll(variants);
        this.active = Math.max(0, Math.min(active, this.variants.size() - 1));
    }

    private static String speakerIdFor(Role sender, Character character) {
        return sender == Role.ASSISTANT && character != null ? character.getId() : "";
    }

    // Every variant is copied, so changing the copy never touches this message.
    ChatMessage copy() {
        List<Variant> copied = new ArrayList<>();
        for (Variant variant : variants) {
            copied.add(new Variant(variant.content, variant.reasoning));
        }
        return new ChatMessage(sender, character, speakerId, imageUrl, copied, active);
    }

    public Role getSender() {
        return sender;
    }

    public void setSender(Role sender) {
        this.sender = sender;
    }

    // Who wrote it. Looked up when asked because a chat is read before every character is loaded. If that character
    // was deleted this is the chat's own character.
    public Character getCharacter() {
        if (!speakerId.isEmpty()) {
            Character speaker = Character.getCharacter(speakerId);
            if (speaker != null) return speaker;
        }
        return character;
    }

    // Empty for the user and for messages from the chat's own character in old chats.
    public String getSpeakerId() {
        return speakerId;
    }

    public String getContent() {
        return variants.get(active).content;
    }

    public void setContent(String content) {
        variants.get(active).content = content;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public boolean hasImage() {
        return imageUrl != null && !imageUrl.isBlank();
    }

    public String getReasoning() {
        return variants.get(active).reasoning;
    }

    public void setReasoning(String reasoning) {
        variants.get(active).reasoning = reasoning;
    }

    public boolean hasReasoning() {
        String reasoning = getReasoning();
        return reasoning != null && !reasoning.isBlank();
    }

    public int getVariantCount() {
        return variants.size();
    }

    public int getActiveIndex() {
        return active;
    }

    public List<Variant> getVariants() {
        return List.copyOf(variants);
    }

    void setActiveIndex(int index) {
        active = Math.max(0, Math.min(index, variants.size() - 1));
    }

    void addVariant(String content, String reasoning) {
        variants.add(new Variant(content, reasoning));
        active = variants.size() - 1;
    }

    // Always keeps at least one variant.
    void removeActiveVariant() {
        if (variants.size() <= 1) return;
        variants.remove(active);
        active = Math.min(active, variants.size() - 1);
    }
}
