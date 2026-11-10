package me.piitex.cca.card;

import me.piitex.cca.App;
import me.piitex.cca.crypto.ProtectedImages;
import me.piitex.cca.model.Character;
import me.piitex.cca.ui.Components;
import me.piitex.engine.ui.color.Color;
import org.jetbrains.skia.Canvas;
import org.jetbrains.skia.Data;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.Paint;
import org.jetbrains.skia.Surface;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Writes a character as a SillyTavern style (Character Card V2) png, the same kind CardImporter reads.
 * The icon is the picture and the card is a base64 json in its "chara" text chunk.
 * <p>
 * The text is written as it is, placeholders stay placeholders. Things only this app has (the placeholders and the
 * reply format and the chat background) go under extensions.cca, other apps ignore that and CardImporter reads it back.
 */
public final class CardExporter {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int FALLBACK_WIDTH = 512, FALLBACK_HEIGHT = 768;
    private static final int BACKGROUND_MAX_SIDE = 1600, BACKGROUND_QUALITY = 85;

    private CardExporter() {
    }

    public static void export(Character character, Path destination) throws IOException {
        byte[] picture = picture(character);
        byte[] json = card(character).toString().getBytes(StandardCharsets.UTF_8);
        Files.write(destination, withCardChunk(picture, Base64.getEncoder().encode(json)));
    }

    // The character's icon as a png, or a plain card in their color when they don't have one.
    private static byte[] picture(Character character) throws IOException {
        Path icon = Path.of(character.getIconPath());
        if (Files.isRegularFile(icon)) {
            Image image = Image.Companion.makeFromEncoded(ProtectedImages.read(icon));
            try {
                return encode(image);
            } finally {
                image.close();
            }
        }
        return fallback(character);
    }

    // The chat background as a jpeg, base64 for the json. It is scaled down since it ends up inside the card.
    // A background that can't be read is left out, the card is still fine without it.
    private static String background(Character character) {
        Path file = character.getBackgroundFile();
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            Image image = Image.Companion.makeFromEncoded(ProtectedImages.read(file));
            try {
                return Base64.getEncoder().encodeToString(scaledJpeg(image));
            } finally {
                image.close();
            }
        } catch (IOException | RuntimeException e) {
            App.logger.warn("Could not add the chat background of '{}' to the card", character.getId(), e);
            return null;
        }
    }

    private static byte[] scaledJpeg(Image image) throws IOException {
        float scale = Math.min(1f, (float) BACKGROUND_MAX_SIDE / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, Math.round(image.getWidth() * scale));
        int height = Math.max(1, Math.round(image.getHeight() * scale));
        try (Surface surface = Surface.Companion.makeRasterN32Premul(width, height)) {
            surface.getCanvas().drawImageRect(image, org.jetbrains.skia.Rect.makeWH(width, height));
            Image scaled = surface.makeImageSnapshot();
            try {
                Data data = scaled.encodeToData(EncodedImageFormat.JPEG, BACKGROUND_QUALITY, 6);
                if (data == null) throw new IOException("Could not turn the background into a jpeg!");
                try {
                    return data.getBytes();
                } finally {
                    data.close();
                }
            } finally {
                scaled.close();
            }
        }
    }

    private static byte[] encode(Image image) throws IOException {
        Data data = image.encodeToData(EncodedImageFormat.PNG, 100, 6);
        if (data == null) throw new IOException("Could not turn the icon into a png!");
        try {
            return data.getBytes();
        } finally {
            data.close();
        }
    }

    private static byte[] fallback(Character character) throws IOException {
        Color[] colors = Components.accentPairFor(character.getId());
        try (Surface surface = Surface.Companion.makeRasterN32Premul(FALLBACK_WIDTH, FALLBACK_HEIGHT);
             Paint paint = new Paint()) {
            paint.setColor(argb(colors[0]));
            Canvas canvas = surface.getCanvas();
            canvas.drawRect(org.jetbrains.skia.Rect.makeWH(FALLBACK_WIDTH, FALLBACK_HEIGHT), paint);
            Image image = surface.makeImageSnapshot();
            try {
                return encode(image);
            } finally {
                image.close();
            }
        }
    }

    private static int argb(Color color) {
        return (Math.round(color.getA() * 255) << 24) | (Math.round(color.getR() * 255) << 16)
                | (Math.round(color.getG() * 255) << 8) | Math.round(color.getB() * 255);
    }

    private static JSONObject card(Character character) {
        JSONObject data = new JSONObject()
                .put("name", character.getDisplayName())
                .put("description", character.getPersona())
                .put("personality", "")
                .put("scenario", character.getChatScenario())
                .put("first_mes", character.getFirstMessage())
                .put("mes_example", "")
                .put("creator_notes", "")
                .put("system_prompt", "")
                .put("post_history_instructions", "")
                .put("alternate_greetings", new JSONArray())
                .put("tags", new JSONArray())
                .put("creator", "")
                .put("character_version", "1.0");
        if (!character.getLorebook().isEmpty()) data.put("character_book", book(character.getLorebook()));

        JSONObject cca = new JSONObject();
        if (!character.getPlaceholders().isEmpty()) cca.put("placeholders", new JSONObject(character.getPlaceholders()));
        if (!character.getReplyFormat().isBlank()) cca.put("replyFormat", character.getReplyFormat());
        if (character.isStrictFormat()) cca.put("strictFormat", true);
        String background = background(character);
        if (background != null) cca.put("background", background);
        data.put("extensions", new JSONObject().put("cca", cca));

        // The first version of the format had these at the top, older apps only look there.
        return new JSONObject()
                .put("spec", "chara_card_v2")
                .put("spec_version", "2.0")
                .put("name", character.getDisplayName())
                .put("description", character.getPersona())
                .put("personality", "")
                .put("scenario", character.getChatScenario())
                .put("first_mes", character.getFirstMessage())
                .put("mes_example", "")
                .put("data", data);
    }

    private static JSONObject book(Map<String, String> lorebook) {
        JSONArray entries = new JSONArray();
        int order = 0;
        for (Map.Entry<String, String> entry : lorebook.entrySet()) {
            JSONArray keys = new JSONArray();
            for (String key : entry.getKey().split(",")) {
                if (!key.isBlank()) keys.put(key.trim());
            }
            entries.put(new JSONObject()
                    .put("id", order)
                    .put("keys", keys)
                    .put("secondary_keys", new JSONArray())
                    .put("content", entry.getValue())
                    .put("comment", "")
                    .put("name", "")
                    .put("enabled", true)
                    .put("insertion_order", order)
                    .put("constant", false)
                    .put("selective", false)
                    .put("extensions", new JSONObject()));
            order++;
        }
        return new JSONObject().put("name", "").put("description", "").put("extensions", new JSONObject()).put("entries", entries);
    }

    // The png with a tEXt chunk "chara" put right after the header. Chunks are length, type, data and a checksum.
    private static byte[] withCardChunk(byte[] png, byte[] base64) throws IOException {
        if (png.length < PNG_SIGNATURE.length + 12) throw new IOException("Not a png!");
        int headerEnd = PNG_SIGNATURE.length + 12 + (readInt(png, PNG_SIGNATURE.length));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(png.length + base64.length + 32);
        bytes.write(png, 0, headerEnd);

        ByteArrayOutputStream text = new ByteArrayOutputStream();
        text.write("chara".getBytes(StandardCharsets.ISO_8859_1));
        text.write(0);
        text.write(base64);
        writeChunk(bytes, "tEXt", text.toByteArray());

        bytes.write(png, headerEnd, png.length - headerEnd);
        return bytes.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream stream = new DataOutputStream(out);
        stream.writeInt(data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        stream.write(typeBytes);
        stream.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        stream.writeInt((int) crc.getValue());
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16) | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }
}
