package me.piitex.cca.model;

import me.piitex.cca.App;
import me.piitex.cca.crypto.FileCrypter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * One conversation, stored as a single encrypted file. The whole chat is rewritten on every change,
 * which is fine for the size of a chat log. The title is stored inside the encrypted data so renaming
 * never touches the file name (the timestamp names are what chats are sorted by).
 * <p>
 * File layout:
 * <pre>
 * v0: int count, then the messages
 * v1: int -1 (marker), int version, utf title, int count, then per message: sender, content, image?, reasoning?
 * v2: same header, then per message: sender, image?, int variants, (content, reasoning?)..., int active
 * v3: like v2 with int members and that many character ids after the title, and the speaker's id (blank for none)
 *     after each message's sender
 * </pre>
 * The first save rewrites an old file as v3.
 * <p>
 * A chat belongs to one character, the owner, whose folder it is in and whose user it uses. A group chat is the same chat
 * with other characters added as members. Without members it is an ordinary chat with one character.
 */
public class Chat {
    private static final int FORMAT_MARKER = -1;
    private static final int FORMAT_VERSION = 3;

    private final Path file;
    private final FileCrypter crypter;
    private final Character owner;
    private final LinkedList<ChatMessage> messages = new LinkedList<>();
    // Ids of the characters besides the owner. Looked up when asked since a chat is read before every character is loaded.
    private final List<String> memberIds = new ArrayList<>();
    private String title = "";

    public Chat(Character character, Path file, FileCrypter crypter) {
        this.owner = character;
        this.file = file;
        this.crypter = crypter;
        loadChat(character);
    }

    private void loadChat(Character character) {
        if (!Files.exists(file)) return;
        try {
            byte[] encrypted = Files.readAllBytes(file);
            if (encrypted.length == 0) return;
            byte[] plain = crypter.decrypt(encrypted);

            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
                int count = in.readInt();
                int version = 0;

                // A negative count can't be a message count, so it marks the newer format.
                if (count == FORMAT_MARKER) {
                    version = in.readInt();
                    if (version > FORMAT_VERSION) {
                        throw new IOException("Chat file format v" + version + " is newer than this app supports");
                    }
                    title = in.readUTF();
                    if (version >= 3) {
                        int members = in.readInt();
                        for (int m = 0; m < members; m++) memberIds.add(in.readUTF());
                    }
                    count = in.readInt();
                }

                for (int i = 0; i < count; i++) {
                    Role sender = Role.valueOf(in.readUTF());
                    String speakerId = version >= 3 ? in.readUTF() : "";
                    if (version >= 2) {
                        String imageUrl = in.readBoolean() ? in.readUTF() : null;
                        int variantCount = in.readInt();
                        List<ChatMessage.Variant> variants = new ArrayList<>();
                        for (int v = 0; v < variantCount; v++) {
                            String content = in.readUTF();
                            String reasoning = in.readBoolean() ? in.readUTF() : null;
                            variants.add(new ChatMessage.Variant(content, reasoning));
                        }
                        int active = in.readInt();
                        if (variants.isEmpty()) variants.add(new ChatMessage.Variant("", null));
                        messages.add(new ChatMessage(sender, character, speakerId, imageUrl, variants, active));
                    } else {
                        String content = in.readUTF();
                        String imageUrl = in.readBoolean() ? in.readUTF() : null;
                        String reasoning = in.readBoolean() ? in.readUTF() : null;
                        messages.add(new ChatMessage(sender, character, content, imageUrl, reasoning));
                    }
                }
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            App.logger.error("Could not read chat file: {}", file, e);
        }
    }

    private void save() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeInt(FORMAT_MARKER);
                out.writeInt(FORMAT_VERSION);
                out.writeUTF(title);
                out.writeInt(memberIds.size());
                for (String id : memberIds) out.writeUTF(id);
                out.writeInt(messages.size());
                for (ChatMessage message : messages) {
                    out.writeUTF(message.getSender().name());
                    out.writeUTF(message.getSpeakerId());
                    out.writeBoolean(message.hasImage());
                    if (message.hasImage()) out.writeUTF(message.getImageUrl());
                    out.writeInt(message.getVariantCount());
                    for (ChatMessage.Variant variant : message.getVariants()) {
                        out.writeUTF(variant.getContent());
                        boolean hasReasoning = variant.getReasoning() != null && !variant.getReasoning().isBlank();
                        out.writeBoolean(hasReasoning);
                        if (hasReasoning) out.writeUTF(variant.getReasoning());
                    }
                    out.writeInt(message.getActiveIndex());
                }
            }
            Files.createDirectories(file.getParent());
            Files.write(file, crypter.encrypt(buffer.toByteArray()));
        } catch (IOException e) {
            App.logger.error("Could not save chat file: {}", file, e);
        }
    }

    public Path getFile() {
        return file;
    }

    // The file name without .dat
    public String getName() {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // Empty if the chat was never renamed.
    public String getTitle() {
        return title;
    }

    // A blank title clears it. This saves even an empty chat so the rename isn't lost.
    public void setTitle(String title) {
        this.title = title == null ? "" : title.trim();
        save();
    }

    // The character this chat belongs to.
    public Character getOwner() {
        return owner;
    }

    // The characters besides the owner. One that was deleted since is left out.
    public List<Character> getMembers() {
        List<Character> members = new ArrayList<>();
        for (String id : memberIds) {
            Character member = Character.getCharacter(id);
            if (member != null) members.add(member);
        }
        return members;
    }

    // The owner first, then the members.
    public List<Character> getParticipants() {
        List<Character> participants = new ArrayList<>();
        participants.add(owner);
        participants.addAll(getMembers());
        return participants;
    }

    // More than one character. A chat without members works exactly like it did before groups.
    public boolean isGroup() {
        return !getMembers().isEmpty();
    }

    // Replaces the members. The owner and repeats are ignored. Saves even an empty chat so the change isn't lost.
    public void setMembers(List<Character> members) {
        memberIds.clear();
        for (Character member : members) {
            if (member == null || member.getId().equals(owner.getId()) || memberIds.contains(member.getId())) continue;
            memberIds.add(member.getId());
        }
        save();
    }

    public LinkedList<ChatMessage> getMessages() {
        return new LinkedList<>(messages);
    }

    public ChatMessage addMessage(Role sender, Character character, String content, String imageUrl, String reasoning) {
        ChatMessage message = new ChatMessage(sender, character, content, imageUrl, reasoning);
        messages.add(message);
        save();
        return message;
    }

    public ChatMessage addMessage(Role sender, Character character, String content) {
        return addMessage(sender, character, content, null, null);
    }

    // Fills this chat with a copy of the source's messages up to and including the one at lastIndex, for branching.
    // Saves even if that's nothing so the chat has a file.
    public void copyMessagesFrom(Chat source, int lastIndex) {
        List<ChatMessage> original = source.getMessages();
        memberIds.clear();
        memberIds.addAll(source.memberIds);
        messages.clear();
        for (int i = 0; i <= lastIndex && i < original.size(); i++) {
            messages.add(original.get(i).copy());
        }
        save();
    }

    public void setMessageContent(int index, String content) {
        if (index < 0 || index >= messages.size()) return;
        messages.get(index).setContent(content);
        save();
    }

    public void removeMessage(int index) {
        if (index < 0 || index >= messages.size()) return;
        messages.remove(index);
        save();
    }

    // Adds another reply to the message and shows it.
    public void addVariant(ChatMessage message, String content, String reasoning) {
        if (!messages.contains(message)) return;
        message.addVariant(content, reasoning);
        save();
    }

    public void setActiveVariant(ChatMessage message, int index) {
        if (!messages.contains(message)) return;
        message.setActiveIndex(index);
        save();
    }

    // Removes only the reply being shown. The message stays as long as it has another one.
    public void removeActiveVariant(ChatMessage message) {
        if (!messages.contains(message)) return;
        message.removeActiveVariant();
        save();
    }
}
