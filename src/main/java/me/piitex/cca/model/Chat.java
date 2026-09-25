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
 * </pre>
 * The first save rewrites an old file as v2.
 */
public class Chat {
    private static final int FORMAT_MARKER = -1;
    private static final int FORMAT_VERSION = 2;

    private final Path file;
    private final FileCrypter crypter;
    private final LinkedList<ChatMessage> messages = new LinkedList<>();
    private String title = "";

    public Chat(Path file, FileCrypter crypter) {
        this.file = file;
        this.crypter = crypter;
        loadChat();
    }

    private void loadChat() {
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
                    count = in.readInt();
                }

                for (int i = 0; i < count; i++) {
                    Role sender = Role.valueOf(in.readUTF());
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
                        messages.add(new ChatMessage(sender, imageUrl, variants, active));
                    } else {
                        String content = in.readUTF();
                        String imageUrl = in.readBoolean() ? in.readUTF() : null;
                        String reasoning = in.readBoolean() ? in.readUTF() : null;
                        messages.add(new ChatMessage(sender, content, imageUrl, reasoning));
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
                out.writeInt(messages.size());
                for (ChatMessage message : messages) {
                    out.writeUTF(message.getSender().name());
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

    public LinkedList<ChatMessage> getMessages() {
        return new LinkedList<>(messages);
    }

    public ChatMessage addMessage(Role sender, String content, String imageUrl, String reasoning) {
        ChatMessage message = new ChatMessage(sender, content, imageUrl, reasoning);
        messages.add(message);
        save();
        return message;
    }

    public ChatMessage addMessage(Role sender, String content) {
        return addMessage(sender, content, null, null);
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
